package com.smartbancs.api.service;

import com.smartbancs.api.dto.LedgerEntryResponse;
import com.smartbancs.api.dto.TransactionResponse;
import com.smartbancs.domain.entity.LedgerEntry;
import com.smartbancs.domain.entity.Transaction;
import com.smartbancs.domain.enums.AccountStatus;
import com.smartbancs.domain.enums.LedgerEntryType;
import com.smartbancs.domain.enums.TransactionStatus;
import com.smartbancs.domain.enums.TransactionType;
import com.smartbancs.infra.persistence.AccountEntity;
import com.smartbancs.infra.persistence.LedgerEntryEntity;
import com.smartbancs.infra.persistence.TransactionEntity;
import com.smartbancs.infra.repository.AccountJpaRepository;
import com.smartbancs.infra.repository.LedgerEntryJpaRepository;
import com.smartbancs.infra.repository.TransactionJpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionJpaRepository transactionRepository;
    private final AccountJpaRepository accountRepository;
    private final LedgerEntryJpaRepository ledgerEntryRepository;
    private final TransactionMetrics metrics;

    public TransactionService(TransactionJpaRepository transactionRepository,
                              AccountJpaRepository accountRepository,
                              LedgerEntryJpaRepository ledgerEntryRepository,
                              TransactionMetrics metrics) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> findAll() {
        return transactionRepository.findAll().stream()
                .map(TransactionEntity::toDomain)
                .map(TransactionService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponse findById(UUID id) {
        return toResponse(findEntity(id).toDomain());
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> findByAccount(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found");
        }
        return transactionRepository.findTop50ByDebitAccountIdOrCreditAccountIdOrderByCreatedAtDesc(accountId, accountId)
                .stream()
                .map(TransactionEntity::toDomain)
                .map(TransactionService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> findLedger(UUID transactionId) {
        findEntity(transactionId);
        return ledgerEntryRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId).stream()
                .map(e -> new LedgerEntryResponse(
                        e.getId(), e.getTransactionId(), e.getAccountId(), e.getType(),
                        e.getAmount(), e.getCurrency(), e.getCreatedAt()))
                .toList();
    }

    @Transactional
    public TransactionResponse create(TransactionType type, BigDecimal amount, String currency,
                                      UUID debitAccountId, UUID creditAccountId,
                                      String idempotencyKey, String reference) {
        long startNanos = metrics.start();
        try {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                TransactionEntity existing = transactionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
                if (existing != null) {
                    metrics.recordIdempotent(existing.toDomain().getType());
                    return toResponse(existing.toDomain());
                }
            }
            TransactionEntity saved = switch (type) {
                case DEPOSIT -> {
                    requireAccount(creditAccountId, "creditAccountId");
                    yield processDeposit(creditAccountId, amount, currency, idempotencyKey, reference);
                }
                case WITHDRAWAL, PAYMENT -> {
                    requireAccount(debitAccountId, "debitAccountId");
                    yield processDebit(type, debitAccountId, amount, currency, idempotencyKey, reference);
                }
                case TRANSFER -> {
                    requireAccount(debitAccountId, "debitAccountId");
                    requireAccount(creditAccountId, "creditAccountId");
                    yield processTransfer(debitAccountId, creditAccountId, amount, currency, idempotencyKey, reference);
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported transaction type");
            };
            TransactionResponse response = toResponse(saved.toDomain());
            metrics.recordSuccess(type, response.amount(), startNanos);
            return response;
        } catch (RuntimeException ex) {
            metrics.recordFailure(type, amount, startNanos);
            throw ex;
        }
    }

    private TransactionEntity processDeposit(UUID creditAccountId, BigDecimal amount, String currency,
                                             String idempotencyKey, String reference) {
        AccountEntity credit = lockedAccount(creditAccountId);
        credit.setBalance(credit.getBalance().add(amount));
        Instant now = Instant.now();
        Transaction tx = buildTransaction(TransactionType.DEPOSIT, amount, currency,
                null, creditAccountId, TransactionStatus.SUCCEEDED, idempotencyKey, reference, null, now);
        accountRepository.save(credit);
        TransactionEntity saved = transactionRepository.save(TransactionEntity.fromDomain(tx));
        ledgerEntryRepository.save(LedgerEntryEntity.fromDomain(
                buildLedger(saved.getId(), creditAccountId, LedgerEntryType.CREDIT, amount, currency, now)));
        return saved;
    }

    private TransactionEntity processDebit(TransactionType type, UUID debitAccountId, BigDecimal amount, String currency,
                                           String idempotencyKey, String reference) {
        AccountEntity debit = lockedAccount(debitAccountId);
        if (debit.getBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient funds");
        }
        debit.setBalance(debit.getBalance().subtract(amount));
        Instant now = Instant.now();
        Transaction tx = buildTransaction(type, amount, currency,
                debitAccountId, null, TransactionStatus.SUCCEEDED, idempotencyKey, reference, null, now);
        accountRepository.save(debit);
        TransactionEntity saved = transactionRepository.save(TransactionEntity.fromDomain(tx));
        ledgerEntryRepository.save(LedgerEntryEntity.fromDomain(
                buildLedger(saved.getId(), debitAccountId, LedgerEntryType.DEBIT, amount, currency, now)));
        return saved;
    }

    private TransactionEntity processTransfer(UUID debitAccountId, UUID creditAccountId, BigDecimal amount,
                                              String currency, String idempotencyKey, String reference) {
        AccountEntity debit = lockedAccount(debitAccountId);
        AccountEntity credit = lockedAccount(creditAccountId);
        if (debit.getBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient funds");
        }
        debit.setBalance(debit.getBalance().subtract(amount));
        credit.setBalance(credit.getBalance().add(amount));
        Instant now = Instant.now();
        Transaction tx = buildTransaction(TransactionType.TRANSFER, amount, currency,
                debitAccountId, creditAccountId, TransactionStatus.SUCCEEDED, idempotencyKey, reference, null, now);
        accountRepository.save(debit);
        accountRepository.save(credit);
        TransactionEntity saved = transactionRepository.save(TransactionEntity.fromDomain(tx));
        ledgerEntryRepository.save(LedgerEntryEntity.fromDomain(
                buildLedger(saved.getId(), debitAccountId, LedgerEntryType.DEBIT, amount, currency, now)));
        ledgerEntryRepository.save(LedgerEntryEntity.fromDomain(
                buildLedger(saved.getId(), creditAccountId, LedgerEntryType.CREDIT, amount, currency, now)));
        return saved;
    }

    private AccountEntity lockedAccount(UUID accountId) {
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "account is not active");
        }
        return account;
    }

    private void requireAccount(UUID accountId, String field) {
        if (accountId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required for type");
        }
    }

    private static Transaction buildTransaction(TransactionType type, BigDecimal amount, String currency,
                                                UUID debitAccountId, UUID creditAccountId, TransactionStatus status,
                                                String idempotencyKey, String reference, String failureReason,
                                                Instant now) {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setType(type);
        tx.setAmount(amount);
        tx.setCurrency(currency.toUpperCase());
        tx.setDebitAccountId(debitAccountId);
        tx.setCreditAccountId(creditAccountId);
        tx.setStatus(status);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setReference(reference);
        tx.setFailureReason(failureReason);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        return tx;
    }

    private static LedgerEntry buildLedger(UUID transactionId, UUID accountId, LedgerEntryType type,
                                           BigDecimal amount, String currency, Instant now) {
        LedgerEntry entry = new LedgerEntry();
        entry.setId(UUID.randomUUID());
        entry.setTransactionId(transactionId);
        entry.setAccountId(accountId);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setCurrency(currency.toUpperCase());
        entry.setCreatedAt(now);
        return entry;
    }

    private TransactionEntity findEntity(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction not found"));
    }

    static TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDebitAccountId(),
                transaction.getCreditAccountId(),
                transaction.getStatus(),
                transaction.getIdempotencyKey(),
                transaction.getReference(),
                transaction.getFailureReason(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt());
    }
}