package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID customerId,
        String accountNumber,
        String currency,
        BigDecimal balance,
        BigDecimal dailyTransferLimit,
        AccountStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}