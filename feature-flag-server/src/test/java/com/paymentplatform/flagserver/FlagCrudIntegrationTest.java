package com.paymentplatform.flagserver;

import com.paymentplatform.flagserver.dto.AuditLogResponse;
import com.paymentplatform.flagserver.dto.CreateFlagRequest;
import com.paymentplatform.flagserver.dto.FlagResponse;
import com.paymentplatform.flagserver.dto.UpdateFlagRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
class FlagCrudIntegrationTest {

    @Container
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-xe:21-slim")
            .withUsername("testuser")
            .withPassword("testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", oracle::getJdbcUrl);
        registry.add("spring.datasource.username", oracle::getUsername);
        registry.add("spring.datasource.password", oracle::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private String uniqueKey() {
        return "flag-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createFlag_andGetByKey() {
        String flagKey = uniqueKey();
        CreateFlagRequest request = new CreateFlagRequest(
                flagKey, "Test Flag", "A test flag", "team-a", 30, null
        );

        ResponseEntity<FlagResponse> createResponse = restTemplate.postForEntity(
                "/api/flags", request, FlagResponse.class
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        FlagResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.flagKey()).isEqualTo(flagKey);
        assertThat(created.name()).isEqualTo("Test Flag");
        assertThat(created.description()).isEqualTo("A test flag");
        assertThat(created.owner()).isEqualTo("team-a");
        assertThat(created.staleAfterDays()).isEqualTo(30);
        assertThat(created.createdAt()).isNotNull();

        ResponseEntity<FlagResponse> getResponse = restTemplate.getForEntity(
                "/api/flags/{flagKey}", FlagResponse.class, flagKey
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        FlagResponse fetched = getResponse.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.flagKey()).isEqualTo(flagKey);
        assertThat(fetched.name()).isEqualTo("Test Flag");
        assertThat(fetched.description()).isEqualTo("A test flag");
        assertThat(fetched.owner()).isEqualTo("team-a");
        assertThat(fetched.staleAfterDays()).isEqualTo(30);
    }

    @Test
    void createFlag_withEnvironments() {
        String flagKey = uniqueKey();
        List<String> environments = List.of("dev", "staging", "prod");
        CreateFlagRequest request = new CreateFlagRequest(
                flagKey, "Env Flag", "Flag with environments", "team-b", null, environments
        );

        ResponseEntity<FlagResponse> createResponse = restTemplate.postForEntity(
                "/api/flags", request, FlagResponse.class
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        FlagResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.environments()).isNotNull();
        assertThat(created.environments()).hasSize(3);
        assertThat(created.environments())
                .extracting("environment")
                .containsExactlyInAnyOrder("dev", "staging", "prod");

        ResponseEntity<FlagResponse> getResponse = restTemplate.getForEntity(
                "/api/flags/{flagKey}", FlagResponse.class, flagKey
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        FlagResponse fetched = getResponse.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.environments()).hasSize(3);
        assertThat(fetched.environments())
                .extracting("environment")
                .containsExactlyInAnyOrder("dev", "staging", "prod");
    }

    @Test
    void createFlag_duplicate_returns409() {
        String flagKey = uniqueKey();
        CreateFlagRequest request = new CreateFlagRequest(
                flagKey, "Original Flag", "First creation", "team-a", null, null
        );

        ResponseEntity<FlagResponse> firstResponse = restTemplate.postForEntity(
                "/api/flags", request, FlagResponse.class
        );
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        CreateFlagRequest duplicateRequest = new CreateFlagRequest(
                flagKey, "Duplicate Flag", "Second creation", "team-b", null, null
        );

        ResponseEntity<String> secondResponse = restTemplate.postForEntity(
                "/api/flags", duplicateRequest, String.class
        );
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listFlags_returnsAll() {
        String flagKey1 = uniqueKey();
        String flagKey2 = uniqueKey();

        restTemplate.postForEntity("/api/flags",
                new CreateFlagRequest(flagKey1, "Flag One", null, null, null, null),
                FlagResponse.class
        );
        restTemplate.postForEntity("/api/flags",
                new CreateFlagRequest(flagKey2, "Flag Two", null, null, null, null),
                FlagResponse.class
        );

        ResponseEntity<List<FlagResponse>> listResponse = restTemplate.exchange(
                "/api/flags", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<FlagResponse>>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<FlagResponse> flags = listResponse.getBody();
        assertThat(flags).isNotNull();
        assertThat(flags)
                .extracting(FlagResponse::flagKey)
                .contains(flagKey1, flagKey2);
    }

    @Test
    void updateFlag() {
        String flagKey = uniqueKey();
        CreateFlagRequest createRequest = new CreateFlagRequest(
                flagKey, "Before Update", "Old description", "team-old", 10, null
        );

        restTemplate.postForEntity("/api/flags", createRequest, FlagResponse.class);

        UpdateFlagRequest updateRequest = new UpdateFlagRequest(
                "After Update", "New description", "team-new", 60
        );

        ResponseEntity<FlagResponse> updateResponse = restTemplate.exchange(
                "/api/flags/{flagKey}", HttpMethod.PUT,
                new HttpEntity<>(updateRequest), FlagResponse.class, flagKey
        );

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        FlagResponse updated = updateResponse.getBody();
        assertThat(updated).isNotNull();
        assertThat(updated.name()).isEqualTo("After Update");
        assertThat(updated.description()).isEqualTo("New description");
        assertThat(updated.owner()).isEqualTo("team-new");
        assertThat(updated.staleAfterDays()).isEqualTo(60);

        ResponseEntity<FlagResponse> getResponse = restTemplate.getForEntity(
                "/api/flags/{flagKey}", FlagResponse.class, flagKey
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        FlagResponse fetched = getResponse.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.name()).isEqualTo("After Update");
        assertThat(fetched.description()).isEqualTo("New description");
        assertThat(fetched.owner()).isEqualTo("team-new");
        assertThat(fetched.staleAfterDays()).isEqualTo(60);
    }

    @Test
    void auditLogCreated_onFlagCreation() {
        String flagKey = uniqueKey();
        CreateFlagRequest request = new CreateFlagRequest(
                flagKey, "Audited Flag", "Should produce audit log", "team-audit", null, null
        );

        ResponseEntity<FlagResponse> createResponse = restTemplate.postForEntity(
                "/api/flags", request, FlagResponse.class
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        FlagResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        String flagId = created.id();

        ResponseEntity<List<AuditLogResponse>> auditResponse = restTemplate.exchange(
                "/api/audit/flag/{flagId}", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<AuditLogResponse>>() {},
                flagId
        );

        assertThat(auditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditLogResponse> auditLogs = auditResponse.getBody();
        assertThat(auditLogs).isNotNull();
        assertThat(auditLogs).isNotEmpty();
        assertThat(auditLogs)
                .extracting(AuditLogResponse::action)
                .contains("CREATED");
        assertThat(auditLogs.get(0).flagId()).isEqualTo(flagId);
        assertThat(auditLogs.get(0).createdAt()).isNotNull();
    }

    @Test
    void liquibaseMigrationsRun() {
        // Verify schema is valid by performing operations that touch all major tables.
        // If Liquibase migrations failed, these operations would throw exceptions.
        String flagKey = uniqueKey();
        CreateFlagRequest request = new CreateFlagRequest(
                flagKey, "Schema Test", "Validates schema", "team-schema", 15,
                List.of("dev", "prod")
        );

        // This exercises: FLAG table, FLAG_ENVIRONMENT table (via environments)
        ResponseEntity<FlagResponse> createResponse = restTemplate.postForEntity(
                "/api/flags", request, FlagResponse.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        FlagResponse created = createResponse.getBody();
        assertThat(created).isNotNull();

        // This exercises: FLAG_AUDIT_LOG table
        ResponseEntity<List<AuditLogResponse>> auditResponse = restTemplate.exchange(
                "/api/audit/flag/{flagId}", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<AuditLogResponse>>() {},
                created.id()
        );
        assertThat(auditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditResponse.getBody()).isNotNull();

        // This exercises: listing with joins across tables
        ResponseEntity<List<FlagResponse>> listResponse = restTemplate.exchange(
                "/api/flags", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<FlagResponse>>() {}
        );
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody()).isNotEmpty();
    }
}
