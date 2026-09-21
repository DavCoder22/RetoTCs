package com.smartbancs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartbancs.api.dto.AiRecommendationContext;
import com.smartbancs.domain.enums.OutboxEventStatus;
import com.smartbancs.infra.persistence.OutboxEventEntity;
import com.smartbancs.infra.repository.OutboxEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Consume el outbox de forma asíncrona y NO bloqueante para el flujo transaccional.
 * - El @Scheduled solo dispara; el @Async ejecuta en hilos virtuales (jar beans no bloquean):
 *   la transacción ya respondió al cliente cuando este worker actúa.
 * - Reintentos con backoff por intento (limite configurable); al superarlo el evento pasa a FAILED.
 * - El agente (Python/FastAPI + OpenRouter) responde un DTO; aquí se persiste en `recommendations`.
 */
@ConditionalOnProperty(name = "smartbancs.ai.worker.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class RecommendationWorker {

    private static final Logger log = LoggerFactory.getLogger(RecommendationWorker.class);
    private static final String AGGREGATE_TYPE = "RECOMMENDATION";
    private static final String EVENT_TYPE = "RECOMMENDATION_REQUEST";

    private final OutboxEventJpaRepository outboxRepository;
    private final RecommendationService recommendationService;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;

    public RecommendationWorker(OutboxEventJpaRepository outboxRepository,
                                RecommendationService recommendationService,
                                ObjectMapper objectMapper,
                                @Value("${smartbancs.ai.worker.batch-size:10}") int batchSize,
                                @Value("${smartbancs.ai.worker.max-attempts:3}") int maxAttempts) {
        this.outboxRepository = outboxRepository;
        this.recommendationService = recommendationService;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${smartbancs.ai.worker.delay-ms:3000}",
            initialDelayString = "${smartbancs.ai.worker.initial-delay-ms:10000}")
    @Async("aiWorkerExecutor")
    public void dispatchPending() {
        List<OutboxEventEntity> pending = outboxRepository
                .findByAggregateTypeAndEventTypeAndStatusOrderByCreatedAtAsc(
                        AGGREGATE_TYPE, EVENT_TYPE, OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));
        for (OutboxEventEntity event : pending) {
            dispatch(event);
        }
    }

    private void dispatch(OutboxEventEntity event) {
        try {
            AiRecommendationContext ctx = objectMapper.readValue(event.getPayload(), AiRecommendationContext.class);
            var response = recommendationService.callAi(ctx);
            recommendationService.persist(ctx, response);
            mark(event, OutboxEventStatus.PROCESSED, null, event.getAttemptCount());
            log.info("recommendation generated tx={} source={} category={}", event.getAggregateId(),
                    response.source(), response.category());
        } catch (Exception ex) {
            int attempts = event.getAttemptCount() + 1;
            if (attempts >= maxAttempts) {
                mark(event, OutboxEventStatus.FAILED, null, attempts);
                log.error("recommendation FAILED after {} attempts tx={}: {}", attempts, event.getAggregateId(),
                        ex.getMessage());
            } else {
                mark(event, OutboxEventStatus.PENDING, null, attempts);
                log.warn("recommendation retry {} tx={}: {}", attempts, event.getAggregateId(), ex.getMessage());
            }
        }
    }

    private void mark(OutboxEventEntity entity, OutboxEventStatus status, Instant processedAt, int attemptCount) {
        entity.setStatus(status);
        entity.setProcessedAt(processedAt != null ? processedAt : Instant.now());
        entity.setAttemptCount(attemptCount);
        outboxRepository.save(entity);
    }
}