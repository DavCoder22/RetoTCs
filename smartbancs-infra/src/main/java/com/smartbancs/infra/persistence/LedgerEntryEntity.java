package com.smartbancs.infra.persistence;

import com.smartbancs.domain.entity.LedgerEntry;
import com.smartbancs.domain.enums.LedgerEntryType;
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
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private LedgerEntryType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntryEntity() {
    }

    public static LedgerEntryEntity fromDomain(LedgerEntry entry) {
        LedgerEntryEntity entity = new LedgerEntryEntity();
        entity.id = entry.getId();
        entity.transactionId = entry.getTransactionId();
        entity.accountId = entry.getAccountId();
        entity.type = entry.getType();
        entity.amount = entry.getAmount();
        entity.currency = entry.getCurrency();
        entity.createdAt = entry.getCreatedAt();
        return entity;
    }

    public LedgerEntry toDomain() {
        LedgerEntry entry = new LedgerEntry();
        entry.setId(id);
        entry.setTransactionId(transactionId);
        entry.setAccountId(accountId);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setCurrency(currency);
        entry.setCreatedAt(createdAt);
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public LedgerEntryType getType() {
        return type;
    }

    public void setType(LedgerEntryType type) {
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}