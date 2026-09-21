package com.smartbancs.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartbancs.api.dto.AiRecommendationContext;
import com.smartbancs.api.dto.RecommendationResponse;
import com.smartbancs.domain.entity.OutboxEvent;
import com.smartbancs.domain.entity.Recommendation;
import com.smartbancs.domain.enums.OutboxEventStatus;
import com.smartbancs.domain.enums.RecommendationStatus;
import com.smartbancs.infra.persistence.OutboxEventEntity;
import com.smartbancs.infra.persistence.RecommendationEntity;
import com.smartbancs.infra.persistence.TransactionEntity;
import com.smartbancs.infra.repository.AccountJpaRepository;
import com.smartbancs.infra.repository.CustomerJpaRepository;
import com.smartbancs.infra.repository.OutboxEventJpaRepository;
import com.smartbancs.infra.repository.RecommendationJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final String AGGREGATE_TYPE = "RECOMMENDATION";
    private static final String EVENT_TYPE = "RECOMMENDATION_REQUEST";

    private final OutboxEventJpaRepository outboxRepository;
    private final AccountJpaRepository accountRepository;
    private final CustomerJpaRepository customerRepository;
    private final RecommendationJpaRepository recommendationRepository;
    private final ObjectMapper objectMapper;
    private final AiGateway ai;

    public RecommendationService(OutboxEventJpaRepository outboxRepository,
                                 AccountJpaRepository accountRepository,
                                 CustomerJpaRepository customerRepository,
                                 RecommendationJpaRepository recommendationRepository,
                                 ObjectMapper objectMapper,
                                 AiGateway ai) {
        this.outboxRepository = outboxRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.recommendationRepository = recommendationRepository;
        this.objectMapper = objectMapper;
        this.ai = ai;
    }

    /**
     * Publica el pedido de recomendación en el outbox DENTRO de la misma
     * transacción de la operación. Así el flujo transaccional NO toca la red:
     * la llamada a la IA ocurre después, en el worker asíncrono.
     */
    public void publishRequest(TransactionEntity saved) {
        try {
            AiRecommendationContext ctx = buildContext(saved);
            OutboxEvent event = new OutboxEvent();
            event.setId(UUID.randomUUID());
            event.setAggregateType(AGGREGATE_TYPE);
            event.setAggregateId(saved.getId().toString());
            event.setEventType(EVENT_TYPE);
            event.setPayload(objectMapper.writeValueAsString(ctx));
            event.setStatus(OutboxEventStatus.PENDING);
            event.setAttemptCount(0);
            event.setCreatedAt(Instant.now());
            outboxRepository.save(OutboxEventEntity.fromDomain(event));
        } catch (JsonProcessingException ex) {
            log.warn("cannot enqueue recommendation request for transaction {}", saved.getId(), ex);
        }
    }

    private AiRecommendationContext buildContext(TransactionEntity saved) {
        Set<UUID> accountIds = new java.util.LinkedHashSet<>();
        if (saved.getDebitAccountId() != null) {
            accountIds.add(saved.getDebitAccountId());
        }
        if (saved.getCreditAccountId() != null) {
            accountIds.add(saved.getCreditAccountId());
        }
        var accounts = accountRepository.findAllById(accountIds);
        var byId = new java.util.HashMap<UUID, com.smartbancs.infra.persistence.AccountEntity>();
        accounts.forEach(a -> byId.put(a.getId(), a));
        var debit = saved.getDebitAccountId() != null ? byId.get(saved.getDebitAccountId()) : null;
        var credit = saved.getCreditAccountId() != null ? byId.get(saved.getCreditAccountId()) : null;
        UUID customerId = debit != null ? debit.getCustomerId()
                : (credit != null ? credit.getCustomerId() : null);
        String segment = null;
        if (customerId != null) {
            var customer = customerRepository.findById(customerId).orElse(null);
            segment = customer != null && customer.getSegment() != null ? customer.getSegment().name() : null;
        }
        var primary = debit != null ? debit : credit;
        Long accountAgeDays = null;
        if (primary != null && primary.getCreatedAt() != null) {
            accountAgeDays = Math.max(0, java.time.Duration.between(primary.getCreatedAt(), Instant.now()).toDays());
        }
        return new AiRecommendationContext(
                saved.getId(),
                customerId,
                segment,
                accountAgeDays,
                saved.getType().name(),
                saved.getAmount(),
                saved.getCurrency(),
                debit != null ? debit.getAccountNumber() : null,
                credit != null ? credit.getAccountNumber() : null,
                debit != null ? debit.getBalance() : null,
                credit != null ? credit.getBalance() : null,
                saved.getReference(),
                saved.getCreatedAt());
    }

    public com.smartbancs.api.dto.AiRecommendationResponse callAi(AiRecommendationContext context) {
        return ai.recommend(context);
    }

    public void persist(AiRecommendationContext ctx, com.smartbancs.api.dto.AiRecommendationResponse aiResponse) {
        Recommendation rec = new Recommendation();
        rec.setId(aiResponse.recommendationId());
        rec.setCustomerId(ctx.customerId());
        rec.setType("AI");
        rec.setStatus(RecommendationStatus.READY);
        rec.setCreatedAt(aiResponse.generatedAt());
        rec.setUpdatedAt(aiResponse.generatedAt());
        try {
            rec.setPayload(objectMapper.writeValueAsString(aiResponse));
        } catch (JsonProcessingException ex) {
            rec.setPayload("{\"error\":\"serialization\"}");
        }
        recommendationRepository.save(RecommendationEntity.fromDomain(rec));
    }

    public List<RecommendationResponse> listByCustomer(UUID customerId, int limit) {
        return recommendationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .limit(limit)
                .map(e -> new RecommendationResponse(
                        e.getId(), e.getCustomerId(), e.getType(), e.getPayload(),
                        e.getStatus(), e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
    }
}