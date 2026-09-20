package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.AccountStatus;

import java.math.BigDecimal;

public record UpdateAccountRequest(
        String currency,
        BigDecimal dailyTransferLimit,
        AccountStatus status) {
}