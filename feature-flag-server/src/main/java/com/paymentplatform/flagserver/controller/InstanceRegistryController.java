package com.paymentplatform.flagserver.controller;

import com.paymentplatform.flagserver.dto.HeartbeatRequest;
import com.paymentplatform.flagserver.dto.InstanceStatusResponse;
import com.paymentplatform.flagserver.dto.RegisterInstanceRequest;
import com.paymentplatform.flagserver.service.InstanceRegistryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/instances")
public class InstanceRegistryController {

    private final InstanceRegistryService instanceRegistryService;

    public InstanceRegistryController(InstanceRegistryService instanceRegistryService) {
        this.instanceRegistryService = instanceRegistryService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public InstanceStatusResponse register(@Valid @RequestBody RegisterInstanceRequest request) {
        return instanceRegistryService.register(request);
    }

    @PostMapping("/heartbeat")
    public InstanceStatusResponse heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        return instanceRegistryService.heartbeat(request);
    }

    @DeleteMapping("/{instanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deregister(@PathVariable String instanceId) {
        instanceRegistryService.deregister(instanceId);
    }

    @GetMapping
    public List<InstanceStatusResponse> getAllInstances() {
        return instanceRegistryService.getAllInstances();
    }

    @GetMapping("/healthy")
    public List<InstanceStatusResponse> getHealthyInstances() {
        return instanceRegistryService.getHealthyInstances();
    }

    @GetMapping("/{instanceId}")
    public InstanceStatusResponse getInstance(@PathVariable String instanceId) {
        return instanceRegistryService.getInstance(instanceId);
    }
}
