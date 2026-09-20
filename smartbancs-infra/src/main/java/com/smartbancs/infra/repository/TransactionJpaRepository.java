package com.smartbancs.infra.repository;

import com.smartbancs.infra.persistence.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionJpaRepository extends JpaRepository<TransactionEntity, UUID> {

    Optional<TransactionEntity> findByIdempotencyKey(String idempotencyKey);

    List<TransactionEntity> findTop50ByDebitAccountIdOrCreditAccountIdOrderByCreatedAtDesc(
            UUID debitAccountId, UUID creditAccountId);
}