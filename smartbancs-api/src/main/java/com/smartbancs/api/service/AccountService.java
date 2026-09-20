package com.smartbancs.api.service;

import com.smartbancs.api.dto.AccountResponse;
import com.smartbancs.domain.entity.Account;
import com.smartbancs.domain.enums.AccountStatus;
import com.smartbancs.infra.persistence.AccountEntity;
import com.smartbancs.infra.repository.AccountJpaRepository;
import com.smartbancs.infra.repository.CustomerJpaRepository;
import com.smartbancs.infra.repository.LedgerEntryJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountJpaRepository accountRepository;
    private final CustomerJpaRepository customerRepository;
    private final LedgerEntryJpaRepository ledgerEntryRepository;

    public AccountService(AccountJpaRepository accountRepository,
                          CustomerJpaRepository customerRepository,
                          LedgerEntryJpaRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> findAll() {
        return accountRepository.findAll().stream()
                .map(AccountEntity::toDomain)
                .map(AccountService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse findById(UUID id) {
        return toResponse(findEntity(id).toDomain());
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> findByCustomer(UUID customerId) {
        return accountRepository.findByCustomerId(customerId).stream()
                .map(AccountEntity::toDomain)
                .map(AccountService::toResponse)
                .toList();
    }

    @Transactional
    public AccountResponse create(UUID customerId, String accountNumber, String currency,
                                  BigDecimal balance, BigDecimal dailyTransferLimit, AccountStatus status) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customer does not exist");
        }
        if (balance.signum() != 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "initial balance must be justified by a DEPOSIT transaction: "
                            + "create the account with balance 0 and register a deposit");
        }
        Instant now = Instant.now();
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setCustomerId(customerId);
        account.setAccountNumber(accountNumber.trim());
        account.setCurrency(currency.toUpperCase());
        account.setBalance(balance);
        account.setDailyTransferLimit(dailyTransferLimit);
        account.setStatus(status);
        account.setVersion(0L);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            return toResponse(accountRepository.save(AccountEntity.fromDomain(account)).toDomain());
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "account number already exists");
        }
    }

    @Transactional
    public AccountResponse update(UUID id, String currency, BigDecimal dailyTransferLimit, AccountStatus status) {
        AccountEntity entity = findEntity(id);
        if (currency != null) {
            entity.setCurrency(currency.toUpperCase());
        }
        if (dailyTransferLimit != null) {
            entity.setDailyTransferLimit(dailyTransferLimit);
        }
        if (status != null) {
            entity.setStatus(status);
        }
        entity.setUpdatedAt(Instant.now());
        return toResponse(accountRepository.save(entity).toDomain());
    }

    @Transactional
    public void delete(UUID id) {
        AccountEntity entity = findEntity(id);
        if (entity.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "account with non-zero balance cannot be deleted");
        }
        if (ledgerEntryRepository.existsByAccountId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "account with movement history cannot be deleted");
        }
        accountRepository.delete(entity);
    }

    private AccountEntity findEntity(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    }

    static AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCustomerId(),
                account.getAccountNumber(),
                account.getCurrency(),
                account.getBalance(),
                account.getDailyTransferLimit(),
                account.getStatus(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}