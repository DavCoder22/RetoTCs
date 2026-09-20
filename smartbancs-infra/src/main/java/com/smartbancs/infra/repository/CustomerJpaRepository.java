package com.smartbancs.infra.repository;

import com.smartbancs.infra.persistence.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> {

    Optional<CustomerEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}