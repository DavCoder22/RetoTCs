package com.smartbancs.infra.repository;

import com.smartbancs.infra.persistence.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    List<LedgerEntryEntity> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    boolean existsByAccountId(UUID accountId);
}