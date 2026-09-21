package com.smartbancs.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartbancs.api.dto.AiRecommendationContext;
import com.smartbancs.api.dto.AiRecommendationResponse;
import com.smartbancs.api.service.AiGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Test de INTEGRACIÓN del flujo completo (contra un PostgreSQL real levantado
 * con Testcontainers + Flyway): POST /customers -> POST /accounts ->
 * POST /transactions -> evento OUTBOX PENDING -> worker asíncrono (gateway IA
 * simulado) -> GET /recommendations devuelve la recomendación en estado READY.
 *
 * El agente de IA real (ai-service/OpenRouter) se sustituye por un @MockBean
 * con el mismo contrato que el mock del agente, de modo que el test verifica el
 * encadenamiento transaccional + outbox + persistencia sin dependencias externas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class RecommendationFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("smartbancs")
            .withUsername("smartbancs")
            .withPassword("smartbancs");

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper om;

    @MockBean
    private AiGateway aiGateway;

    @Test
    void depositGeneratesRecommendationAsynchronously() throws Exception {
        Mockito.when(aiGateway.recommend(any(AiRecommendationContext.class))).thenAnswer(inv -> {
            AiRecommendationContext ctx = inv.getArgument(0);
            return new AiRecommendationResponse(
                    UUID.randomUUID(), ctx.customerId(), "savings",
                    "recomendacion de prueba (it)", "MEDIUM",
                    List.of("insight-test"), List.of("accion-test"),
                    "heuristic-mock-v1", "mock", Instant.now());
        });

        // 1) cliente
        String email = "it-" + UUID.randomUUID() + "@reto.test";
        ResponseEntity<String> cust = rest.postForEntity("/customers",
                Map.of("fullName", "IT Cliente", "email", email, "segment", "RETAIL"), String.class);
        assertThat(cust.getStatusCode()).as("POST /customers").isEqualTo(HttpStatus.CREATED);
        UUID customerId = UUID.fromString(om.readTree(cust.getBody()).get("id").asText());

        // 2) cuenta
        ResponseEntity<String> acc = rest.postForEntity("/accounts", Map.of(
                "customerId", customerId.toString(),
                "accountNumber", "IT-" + System.nanoTime(),
                "currency", "PEN",
                "balance", 0,
                "dailyTransferLimit", 5000,
                "status", "ACTIVE"), String.class);
        assertThat(acc.getStatusCode()).as("POST /accounts").isEqualTo(HttpStatus.CREATED);
        UUID accountId = UUID.fromString(om.readTree(acc.getBody()).get("id").asText());

        // 3) transacción DEPOSIT
        ResponseEntity<String> tx = rest.postForEntity("/transactions", Map.of(
                "type", "DEPOSIT",
                "amount", new BigDecimal("750.00"),
                "currency", "PEN",
                "creditAccountId", accountId.toString(),
                "reference", "it-test"), String.class);
        assertThat(tx.getStatusCode()).as("POST /transactions").isEqualTo(HttpStatus.CREATED);
        JsonNode txBody = om.readTree(tx.getBody());
        assertThat(txBody.get("status").asText()).isEqualTo("SUCCEEDED");
        UUID txId = UUID.fromString(txBody.get("id").asText());

        // 4) el outbox queda encolado (PENDING) para esa transacción
        Integer enqueued = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, txId.toString());
        assertThat(enqueued).as("evento outbox encolado").isGreaterThanOrEqualTo(1);

        // 5) el worker asíncrono lo procesa y persiste la recomendación (READY)
        boolean ready = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline && !ready) {
            Integer processed = jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PROCESSED'",
                    Integer.class, txId.toString());
            ready = processed != null && processed > 0;
            if (!ready) {
                Thread.sleep(500);
            }
        }
        assertThat(ready).as("el worker procesa el evento outbox").isTrue();

        ResponseEntity<String> rec = rest.getForEntity(
                "/recommendations?customerId=" + customerId, String.class);
        assertThat(rec.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = om.readTree(rec.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).as("hay al menos una recomendación").isGreaterThanOrEqualTo(1);
        JsonNode first = body.get(0);
        assertThat(first.get("status").asText()).isEqualTo("READY");
        assertThat(first.get("type").asText()).isEqualTo("AI");
        assertThat(first.get("payload").asText()).contains("savings");
    }
}
