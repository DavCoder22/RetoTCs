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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Test de INTEGRACIÓN del contrato de negocio de cuentas: número de cuenta
 * duplicado -> 409 CONFLICT (el insert con el mismo accountNumber no debe
 * derivar en 500 por violación de constraint en el commit).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AccountCreateConflictTest {

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
    void duplicateAccountNumberReturns409Conflict() throws Exception {
        Mockito.when(aiGateway.recommend(any())).thenAnswer(inv ->
                new AiRecommendationResponse(UUID.randomUUID(), UUID.randomUUID(), "generic",
                        "rec", "LOW", List.of(), List.of(), "mock", "mock", Instant.now()));

        String email = "dup-acc-" + UUID.randomUUID() + "@reto.test";
        ResponseEntity<String> cust = rest.postForEntity("/customers",
                Map.of("fullName", "Cliente Dup", "email", email, "segment", "RETAIL"), String.class);
        assertThat(cust.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID customerId = UUID.fromString(om.readTree(cust.getBody()).get("id").asText());

        String accountNumber = "DUP-" + System.nanoTime();
        Map<String, Object> payload = Map.of(
                "customerId", customerId.toString(),
                "accountNumber", accountNumber,
                "currency", "PEN",
                "balance", 0,
                "dailyTransferLimit", 0,
                "status", "ACTIVE");

        ResponseEntity<String> first = rest.postForEntity("/accounts", payload, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = rest.postForEntity("/accounts", payload, String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = om.readTree(duplicate.getBody());
        assertThat(body.get("detail").asText()).contains("account number already exists");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE account_number = ?",
                Integer.class, accountNumber);
        assertThat(count).as("una sola cuenta con ese número").isEqualTo(1);
    }
}