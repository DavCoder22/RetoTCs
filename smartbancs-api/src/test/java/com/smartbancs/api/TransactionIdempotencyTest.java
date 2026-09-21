package com.smartbancs.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Test de INTEGRACIÓN de la idempotencia transaccional: reenviar el mismo
 * depósito con la misma `idempotencyKey` no crea una segunda transacción ni un
 * doble movimiento de saldo (el servicio devuelve la transacción original).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TransactionIdempotencyTest {

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
    void duplicateDepositWithSameIdempotencyKeyIsNotDuplicated() throws Exception {
        Mockito.when(aiGateway.recommend(any())).thenAnswer(inv ->
                new AiRecommendationResponse(UUID.randomUUID(), UUID.randomUUID(), "generic",
                        "rec", "LOW", List.of(), List.of(), "mock", "mock", Instant.now()));

        String email = "idem-" + UUID.randomUUID() + "@reto.test";
        ResponseEntity<String> cust = rest.postForEntity("/customers",
                Map.of("fullName", "Idem Cliente", "email", email, "segment", "PREMIUM"), String.class);
        assertThat(cust.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID customerId = UUID.fromString(om.readTree(cust.getBody()).get("id").asText());

        ResponseEntity<String> acc = rest.postForEntity("/accounts", Map.of(
                "customerId", customerId.toString(),
                "accountNumber", "IDEM-" + System.nanoTime(),
                "currency", "PEN",
                "balance", 0,
                "dailyTransferLimit", 5000,
                "status", "ACTIVE"), String.class);
        assertThat(acc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID accountId = UUID.fromString(om.readTree(acc.getBody()).get("id").asText());

        String key = "idem-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "type", "DEPOSIT",
                "amount", new BigDecimal("100.00"),
                "currency", "PEN",
                "creditAccountId", accountId.toString(),
                "idempotencyKey", key,
                "reference", "idem-test");

        ResponseEntity<String> first = rest.postForEntity("/transactions", payload, String.class);
        ResponseEntity<String> second = rest.postForEntity("/transactions", payload, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID firstId = UUID.fromString(om.readTree(first.getBody()).get("id").asText());
        UUID secondId = UUID.fromString(om.readTree(second.getBody()).get("id").asText());
        assertThat(secondId).as("mismo id de transacción en el reenvío").isEqualTo(firstId);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM transactions WHERE idempotency_key = ?",
                Integer.class, key);
        assertThat(count).as("una sola fila para la idempotencyKey").isEqualTo(1);
    }
}
