package com.smartbancs.infra.repository;

import com.smartbancs.infra.persistence.RecommendationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecommendationJpaRepository extends JpaRepository<RecommendationEntity, UUID> {

    List<RecommendationEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}