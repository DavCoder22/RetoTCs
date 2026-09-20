package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.TransactionStatus;
import com.smartbancs.domain.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        TransactionType type,
        BigDecimal amount,
        String currency,
        UUID debitAccountId,
        UUID creditAccountId,
        TransactionStatus status,
        String idempotencyKey,
        String reference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}