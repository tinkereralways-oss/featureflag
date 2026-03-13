package com.paymentplatform.flagsdk.client;

import com.paymentplatform.flagsdk.config.FeatureFlagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlagServerClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private Environment environment;

    private FeatureFlagProperties properties;
    private FlagServerClient client;

    @BeforeEach
    void setUp() {
        properties = new FeatureFlagProperties();
        properties.setServerUrl("http://localhost:8080");
        properties.setServiceName("test-service");
        properties.setInstanceId("test-instance-1");
        client = new FlagServerClient(restTemplate, properties, environment);
    }

    @Test
    void syncFlags_parsesServerResponse() {
        List<Map<String, Object>> serverResponse = List.of(
                Map.of(
                        "flagKey", "flag-a",
                        "environments", List.of(
                                Map.of("environment", "prod", "enabled", true),
                                Map.of("environment", "staging", "enabled", false)
                        )
                )
        );

        when(restTemplate.exchange(
                eq("http://localhost:8080/api/flags"),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)
        )).thenReturn(ResponseEntity.ok(serverResponse));

        Map<String, Map<String, Boolean>> result = client.syncFlags();

        assertEquals(1, result.size());
        assertTrue(result.get("flag-a").get("prod"));
        assertFalse(result.get("flag-a").get("staging"));
    }

    @Test
    void syncFlags_returnsEmptyMapOnError() {
        when(restTemplate.exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RestClientException("Connection refused"));

        Map<String, Map<String, Boolean>> result = client.syncFlags();
        assertTrue(result.isEmpty());
    }

    @Test
    void register_sendsCorrectRequest() {
        client.register();

        verify(restTemplate).postForObject(
                eq("http://localhost:8080/api/instances/register"),
                argThat(arg -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) arg;
                    return "test-instance-1".equals(map.get("instanceId"))
                            && "test-service".equals(map.get("serviceName"));
                }),
                eq(Map.class)
        );
    }

    @Test
    void heartbeat_sendsCorrectRequest() {
        client.heartbeat();

        verify(restTemplate).postForObject(
                eq("http://localhost:8080/api/instances/heartbeat"),
                eq(Map.of("instanceId", "test-instance-1")),
                eq(Map.class)
        );
    }

    @Test
    void ack_sendsCorrectRequest() {
        client.ack("activation-123");

        verify(restTemplate).postForObject(
                eq("http://localhost:8080/api/activations/activation-123/ack"),
                eq(Map.of("instanceId", "test-instance-1")),
                eq(Map.class)
        );
    }

    @Test
    void deregister_sendsDeleteRequest() {
        client.deregister();

        verify(restTemplate).delete("http://localhost:8080/api/instances/test-instance-1");
    }

    @Test
    void register_handlesError() {
        doThrow(new RestClientException("Connection refused"))
                .when(restTemplate).postForObject(anyString(), any(), any());

        // Should not throw
        assertDoesNotThrow(() -> client.register());
    }
}
