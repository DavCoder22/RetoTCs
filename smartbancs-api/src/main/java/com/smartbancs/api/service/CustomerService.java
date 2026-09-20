package com.smartbancs.api.service;

import com.smartbancs.api.dto.CustomerResponse;
import com.smartbancs.domain.entity.Customer;
import com.smartbancs.domain.enums.CustomerSegment;
import com.smartbancs.infra.persistence.CustomerEntity;
import com.smartbancs.infra.repository.AccountJpaRepository;
import com.smartbancs.infra.repository.CustomerJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerJpaRepository customerRepository;
    private final AccountJpaRepository accountRepository;

    public CustomerService(CustomerJpaRepository customerRepository, AccountJpaRepository accountRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> findAll() {
        return customerRepository.findAll().stream()
                .map(CustomerEntity::toDomain)
                .map(CustomerService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerResponse findById(UUID id) {
        return toResponse(findEntity(id).toDomain());
    }

    @Transactional
    public CustomerResponse create(String fullName, String email, CustomerSegment segment) {
        if (customerRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        Instant now = Instant.now();
        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setFullName(fullName.trim());
        customer.setEmail(email.trim());
        customer.setSegment(segment);
        customer.setCreatedAt(now);
        customer.setUpdatedAt(now);
        try {
            return toResponse(customerRepository.save(CustomerEntity.fromDomain(customer)).toDomain());
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
    }

    @Transactional
    public CustomerResponse update(UUID id, String fullName, String email, CustomerSegment segment) {
        CustomerEntity entity = findEntity(id);
        boolean emailChanged = !entity.getEmail().equalsIgnoreCase(email);
        if (emailChanged && customerRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        entity.setFullName(fullName.trim());
        entity.setEmail(email.trim());
        entity.setSegment(segment);
        entity.setUpdatedAt(Instant.now());
        try {
            return toResponse(customerRepository.save(entity).toDomain());
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
    }

    @Transactional
    public void delete(UUID id) {
        CustomerEntity entity = findEntity(id);
        if (!accountRepository.findByCustomerId(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "customer cannot be deleted while it still has accounts");
        }
        customerRepository.delete(entity);
    }

    private CustomerEntity findEntity(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "customer not found"));
    }

    static CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail(),
                customer.getSegment(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }
}