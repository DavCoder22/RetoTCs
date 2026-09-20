package com.smartbancs.api.dto;

import com.smartbancs.domain.enums.CustomerSegment;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String fullName,
        String email,
        CustomerSegment segment,
        Instant createdAt,
        Instant updatedAt) {
}