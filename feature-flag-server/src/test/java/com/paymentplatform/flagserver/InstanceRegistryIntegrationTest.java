package com.paymentplatform.flagserver;

import com.paymentplatform.flagserver.dto.HeartbeatRequest;
import com.paymentplatform.flagserver.dto.InstanceStatusResponse;
import com.paymentplatform.flagserver.dto.RegisterInstanceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
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
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "feature-flag.unhealthy-threshold-seconds=2"
})
class InstanceRegistryIntegrationTest {

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

    private String uniqueInstanceId() {
        return UUID.randomUUID().toString();
    }

    @Test
    void registerInstance_andGetById() {
        String instanceId = uniqueInstanceId();
        RegisterInstanceRequest request = new RegisterInstanceRequest(
                instanceId, "payment-service", "192.168.1.10", 8080, "1.0.0"
        );

        ResponseEntity<InstanceStatusResponse> registerResponse = restTemplate.postForEntity(
                "/api/instances/register", request, InstanceStatusResponse.class
        );

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        InstanceStatusResponse registered = registerResponse.getBody();
        assertThat(registered).isNotNull();
        assertThat(registered.instanceId()).isEqualTo(instanceId);
        assertThat(registered.serviceName()).isEqualTo("payment-service");
        assertThat(registered.hostAddress()).isEqualTo("192.168.1.10");
        assertThat(registered.port()).isEqualTo(8080);
        assertThat(registered.healthStatus()).isEqualTo("HEALTHY");
        assertThat(registered.sdkVersion()).isEqualTo("1.0.0");
        assertThat(registered.lastHeartbeat()).isNotNull();
        assertThat(registered.registeredAt()).isNotNull();

        ResponseEntity<InstanceStatusResponse> getResponse = restTemplate.getForEntity(
                "/api/instances/{instanceId}", InstanceStatusResponse.class, instanceId
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        InstanceStatusResponse fetched = getResponse.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.instanceId()).isEqualTo(instanceId);
        assertThat(fetched.serviceName()).isEqualTo("payment-service");
        assertThat(fetched.healthStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void registerInstance_reRegisterExisting() {
        String instanceId = uniqueInstanceId();
        RegisterInstanceRequest request1 = new RegisterInstanceRequest(
                instanceId, "old-service", "10.0.0.1", 8080, "1.0.0"
        );

        restTemplate.postForEntity("/api/instances/register", request1, InstanceStatusResponse.class);

        RegisterInstanceRequest request2 = new RegisterInstanceRequest(
                instanceId, "new-service", "10.0.0.2", 9090, "2.0.0"
        );

        ResponseEntity<InstanceStatusResponse> reRegisterResponse = restTemplate.postForEntity(
                "/api/instances/register", request2, InstanceStatusResponse.class
        );

        assertThat(reRegisterResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        InstanceStatusResponse response = reRegisterResponse.getBody();
        assertThat(response).isNotNull();
        assertThat(response.instanceId()).isEqualTo(instanceId);
        assertThat(response.serviceName()).isEqualTo("new-service");
        assertThat(response.hostAddress()).isEqualTo("10.0.0.2");
        assertThat(response.port()).isEqualTo(9090);
        assertThat(response.sdkVersion()).isEqualTo("2.0.0");
        assertThat(response.healthStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void heartbeat_updatesLastHeartbeat() {
        String instanceId = uniqueInstanceId();
        RegisterInstanceRequest registerRequest = new RegisterInstanceRequest(
                instanceId, "payment-service", "192.168.1.10", 8080, "1.0.0"
        );

        ResponseEntity<InstanceStatusResponse> registerResponse = restTemplate.postForEntity(
                "/api/instances/register", registerRequest, InstanceStatusResponse.class
        );
        assertThat(registerResponse.getBody()).isNotNull();
        var initialHeartbeat = registerResponse.getBody().lastHeartbeat();

        HeartbeatRequest heartbeatRequest = new HeartbeatRequest(instanceId);

        ResponseEntity<InstanceStatusResponse> heartbeatResponse = restTemplate.postForEntity(
                "/api/instances/heartbeat", heartbeatRequest, InstanceStatusResponse.class
        );

        assertThat(heartbeatResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        InstanceStatusResponse response = heartbeatResponse.getBody();
        assertThat(response).isNotNull();
        assertThat(response.instanceId()).isEqualTo(instanceId);
        assertThat(response.healthStatus()).isEqualTo("HEALTHY");
        assertThat(response.lastHeartbeat()).isAfterOrEqualTo(initialHeartbeat);
    }

    @Test
    void heartbeat_unknownInstance_returns404() {
        HeartbeatRequest heartbeatRequest = new HeartbeatRequest("nonexistent-instance");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/instances/heartbeat", heartbeatRequest, String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deregister_removesInstance() {
        String instanceId = uniqueInstanceId();
        RegisterInstanceRequest registerRequest = new RegisterInstanceRequest(
                instanceId, "payment-service", "192.168.1.10", 8080, "1.0.0"
        );

        restTemplate.postForEntity("/api/instances/register", registerRequest, InstanceStatusResponse.class);

        restTemplate.delete("/api/instances/{instanceId}", instanceId);

        ResponseEntity<String> getResponse = restTemplate.getForEntity(
                "/api/instances/{instanceId}", String.class, instanceId
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deregister_unknownInstance_returns404() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/instances/{instanceId}", HttpMethod.DELETE, null, String.class, "nonexistent"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listAllInstances() {
        String instanceId1 = uniqueInstanceId();
        String instanceId2 = uniqueInstanceId();

        restTemplate.postForEntity("/api/instances/register",
                new RegisterInstanceRequest(instanceId1, "svc-a", "10.0.0.1", 8080, "1.0.0"),
                InstanceStatusResponse.class
        );
        restTemplate.postForEntity("/api/instances/register",
                new RegisterInstanceRequest(instanceId2, "svc-b", "10.0.0.2", 8081, "1.0.0"),
                InstanceStatusResponse.class
        );

        ResponseEntity<List<InstanceStatusResponse>> listResponse = restTemplate.exchange(
                "/api/instances", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<InstanceStatusResponse>>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<InstanceStatusResponse> instances = listResponse.getBody();
        assertThat(instances).isNotNull();
        assertThat(instances)
                .extracting(InstanceStatusResponse::instanceId)
                .contains(instanceId1, instanceId2);
    }

    @Test
    void getHealthyInstances_filtersCorrectly() {
        String instanceId = uniqueInstanceId();

        restTemplate.postForEntity("/api/instances/register",
                new RegisterInstanceRequest(instanceId, "healthy-svc", "10.0.0.1", 8080, "1.0.0"),
                InstanceStatusResponse.class
        );

        ResponseEntity<List<InstanceStatusResponse>> healthyResponse = restTemplate.exchange(
                "/api/instances/healthy", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<InstanceStatusResponse>>() {}
        );

        assertThat(healthyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<InstanceStatusResponse> healthyInstances = healthyResponse.getBody();
        assertThat(healthyInstances).isNotNull();
        assertThat(healthyInstances)
                .extracting(InstanceStatusResponse::instanceId)
                .contains(instanceId);
        healthyInstances.forEach(inst ->
                assertThat(inst.healthStatus()).isEqualTo("HEALTHY")
        );
    }
}
