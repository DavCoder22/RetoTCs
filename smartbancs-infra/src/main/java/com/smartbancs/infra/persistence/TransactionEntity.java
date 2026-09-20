package com.smartbancs.infra.persistence;

import com.smartbancs.domain.entity.Transaction;
import com.smartbancs.domain.enums.TransactionStatus;
import com.smartbancs.domain.enums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "debit_account_id")
    private UUID debitAccountId;

    @Column(name = "credit_account_id")
    private UUID creditAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(length = 100)
    private String reference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TransactionEntity() {
    }

    public static TransactionEntity fromDomain(Transaction transaction) {
        TransactionEntity entity = new TransactionEntity();
        entity.id = transaction.getId();
        entity.type = transaction.getType();
        entity.amount = transaction.getAmount();
        entity.currency = transaction.getCurrency();
        entity.debitAccountId = transaction.getDebitAccountId();
        entity.creditAccountId = transaction.getCreditAccountId();
        entity.status = transaction.getStatus();
        entity.idempotencyKey = transaction.getIdempotencyKey();
        entity.reference = transaction.getReference();
        entity.failureReason = transaction.getFailureReason();
        entity.createdAt = transaction.getCreatedAt();
        entity.updatedAt = transaction.getUpdatedAt();
        return entity;
    }

    public Transaction toDomain() {
        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setType(type);
        transaction.setAmount(amount);
        transaction.setCurrency(currency);
        transaction.setDebitAccountId(debitAccountId);
        transaction.setCreditAccountId(creditAccountId);
        transaction.setStatus(status);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setReference(reference);
        transaction.setFailureReason(failureReason);
        transaction.setCreatedAt(createdAt);
        transaction.setUpdatedAt(updatedAt);
        return transaction;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public UUID getDebitAccountId() {
        return debitAccountId;
    }

    public void setDebitAccountId(UUID debitAccountId) {
        this.debitAccountId = debitAccountId;
    }

    public UUID getCreditAccountId() {
        return creditAccountId;
    }

    public void setCreditAccountId(UUID creditAccountId) {
        this.creditAccountId = creditAccountId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}