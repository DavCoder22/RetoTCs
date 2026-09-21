package com.smartbancs.api.service;

import com.smartbancs.domain.enums.TransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom business metrics for the transaction flow. All metrics are tagged with
 * the transaction type so they can be sliced in Prometheus/Grafana.
 */
@Component
public class TransactionMetrics {

    private static final String METRIC_TOTAL = "smartbancs.transactions.total";
    private static final String METRIC_DURATION = "smartbancs.transaction.duration";
    private static final String METRIC_AMOUNT = "smartbancs.transaction.amount";

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

    public TransactionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public long start() {
        return System.nanoTime();
    }

    public void recordSuccess(TransactionType type, BigDecimal amount, long startNanos) {
        record(type, "success", amount, Duration.ofNanos(System.nanoTime() - startNanos));
    }

    public void recordFailure(TransactionType type, BigDecimal amount, long startNanos) {
        record(type, "error", amount, Duration.ofNanos(System.nanoTime() - startNanos));
    }

    public void recordIdempotent(TransactionType type) {
        record(type, "idempotent", null, null);
    }

    private void record(TransactionType type, String outcome, BigDecimal amount, Duration duration) {
        String typeName = type != null ? type.name() : "UNKNOWN";
        counter(typeName, outcome).increment();
        if (amount != null) {
            summary(typeName).record(amount.doubleValue());
        }
        if (duration != null) {
            timer(typeName).record(duration);
        }
    }

    private Counter counter(String type, String outcome) {
        return counters.computeIfAbsent(type + "|" + outcome,
                key -> Counter.builder(METRIC_TOTAL)
                        .description("Transactions handled by type and outcome")
                        .tags("type", type, "outcome", outcome)
                        .register(registry));
    }

    private Timer timer(String type) {
        return timers.computeIfAbsent(type,
                key -> Timer.builder(METRIC_DURATION)
                        .description("Time spent processing a transaction by type")
                        .tags("type", type)
                        .register(registry));
    }

    private DistributionSummary summary(String type) {
        return summaries.computeIfAbsent(type,
                key -> DistributionSummary.builder(METRIC_AMOUNT)
                        .description("Transaction amounts by type")
                        .baseUnit("amount")
                        .tags("type", type)
                        .register(registry));
    }
}