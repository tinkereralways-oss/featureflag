package com.paymentplatform.flagsdk.client;

import com.paymentplatform.flagsdk.config.FeatureFlagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlagServerClient {

    private static final Logger log = LoggerFactory.getLogger(FlagServerClient.class);

    private final RestTemplate restTemplate;
    private final FeatureFlagProperties properties;
    private final Environment environment;

    public FlagServerClient(RestTemplate restTemplate, FeatureFlagProperties properties, Environment environment) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * Fetches all flags from the server and returns a map of flagKey -> (environment -> enabled).
     */
    public Map<String, Map<String, Boolean>> syncFlags() {
        String url = properties.getServerUrl() + "/api/flags";
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );

            List<Map<String, Object>> flags = response.getBody();
            if (flags == null) {
                return Map.of();
            }

            Map<String, Map<String, Boolean>> result = new HashMap<>();
            for (Map<String, Object> flag : flags) {
                String flagKey = (String) flag.get("flagKey");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> environments = (List<Map<String, Object>>) flag.get("environments");
                if (environments != null) {
                    Map<String, Boolean> envMap = new HashMap<>();
                    for (Map<String, Object> env : environments) {
                        String envName = (String) env.get("environment");
                        Boolean enabled = (Boolean) env.get("enabled");
                        envMap.put(envName, enabled != null && enabled);
                    }
                    result.put(flagKey, envMap);
                }
            }
            log.info("Synced {} flags from server", result.size());
            return result;
        } catch (RestClientException e) {
            log.error("Failed to sync flags from server: {}", e.getMessage());
            return Map.of();
        }
    }

    public void register() {
        String url = properties.getServerUrl() + "/api/instances/register";
        Map<String, Object> request = new HashMap<>();
        request.put("instanceId", properties.getInstanceId());
        request.put("serviceName", properties.getServiceName());
        request.put("hostAddress", getHostAddress());
        request.put("port", getServerPort());
        request.put("sdkVersion", "1.0.0");

        try {
            restTemplate.postForObject(url, request, Map.class);
            log.info("Registered instance {} with server", properties.getInstanceId());
        } catch (RestClientException e) {
            log.error("Failed to register with server: {}", e.getMessage());
        }
    }

    public void heartbeat() {
        String url = properties.getServerUrl() + "/api/instances/heartbeat";
        Map<String, String> request = Map.of("instanceId", properties.getInstanceId());

        try {
            restTemplate.postForObject(url, request, Map.class);
            log.debug("Heartbeat sent for instance {}", properties.getInstanceId());
        } catch (RestClientException e) {
            log.warn("Failed to send heartbeat: {}", e.getMessage());
        }
    }

    public void ack(String activationId) {
        String url = properties.getServerUrl() + "/api/activations/" + activationId + "/ack";
        Map<String, String> request = Map.of("instanceId", properties.getInstanceId());

        try {
            restTemplate.postForObject(url, request, Map.class);
            log.info("ACK sent for activation {} from instance {}", activationId, properties.getInstanceId());
        } catch (RestClientException e) {
            log.error("Failed to ACK activation {}: {}", activationId, e.getMessage());
        }
    }

    public void deregister() {
        String url = properties.getServerUrl() + "/api/instances/" + properties.getInstanceId();
        try {
            restTemplate.delete(url);
            log.info("Deregistered instance {}", properties.getInstanceId());
        } catch (RestClientException e) {
            log.warn("Failed to deregister instance: {}", e.getMessage());
        }
    }

    private String getHostAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private Integer getServerPort() {
        String port = environment.getProperty("server.port", "8080");
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return 8080;
        }
    }
}
