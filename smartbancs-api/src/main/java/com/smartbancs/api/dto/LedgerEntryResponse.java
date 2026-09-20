package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.LedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID transactionId,
        UUID accountId,
        LedgerEntryType type,
        BigDecimal amount,
        String currency,
        Instant createdAt) {
}