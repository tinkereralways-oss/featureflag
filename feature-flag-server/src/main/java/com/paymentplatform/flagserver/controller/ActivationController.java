package com.paymentplatform.flagserver.controller;

import com.paymentplatform.flagserver.dto.AckRequest;
import com.paymentplatform.flagserver.dto.ActivationResponse;
import com.paymentplatform.flagserver.service.TwoPhaseActivationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/activations")
public class ActivationController {

    private final TwoPhaseActivationService activationService;

    public ActivationController(TwoPhaseActivationService activationService) {
        this.activationService = activationService;
    }

    @PostMapping("/{activationId}/ack")
    public ActivationResponse ack(@PathVariable String activationId,
                                  @Valid @RequestBody AckRequest request) {
        return activationService.receiveAck(activationId, request.instanceId());
    }

    @GetMapping("/{activationId}")
    public ActivationResponse getActivation(@PathVariable String activationId) {
        return activationService.getActivation(activationId);
    }

    @GetMapping("/pending")
    public List<ActivationResponse> getPendingActivations() {
        return activationService.getPendingActivations();
    }
}
