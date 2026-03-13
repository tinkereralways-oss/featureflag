package com.paymentplatform.flagserver.controller;

import com.paymentplatform.flagserver.dto.ActivationResponse;
import com.paymentplatform.flagserver.dto.CreateFlagRequest;
import com.paymentplatform.flagserver.dto.FlagResponse;
import com.paymentplatform.flagserver.dto.LifecycleTransitionRequest;
import com.paymentplatform.flagserver.dto.LifecycleTransitionResponse;
import com.paymentplatform.flagserver.dto.ToggleFlagRequest;
import com.paymentplatform.flagserver.dto.UpdateFlagRequest;
import com.paymentplatform.flagserver.service.FlagLifecycleService;
import com.paymentplatform.flagserver.service.FlagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/flags")
public class FlagController {

    private final FlagService flagService;
    private final FlagLifecycleService lifecycleService;

    public FlagController(FlagService flagService, FlagLifecycleService lifecycleService) {
        this.flagService = flagService;
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlagResponse createFlag(@Valid @RequestBody CreateFlagRequest request) {
        return flagService.createFlag(request);
    }

    @GetMapping
    public List<FlagResponse> listFlags() {
        return flagService.listFlags();
    }

    @GetMapping("/{flagKey}")
    public FlagResponse getFlag(@PathVariable String flagKey) {
        return flagService.getFlag(flagKey);
    }

    @PutMapping("/{flagKey}")
    public FlagResponse updateFlag(@PathVariable String flagKey,
                                   @Valid @RequestBody UpdateFlagRequest request) {
        return flagService.updateFlag(flagKey, request);
    }

    @PutMapping("/{flagKey}/environments/{environment}")
    public ActivationResponse toggleFlag(@PathVariable String flagKey,
                                         @PathVariable String environment,
                                         @Valid @RequestBody ToggleFlagRequest request) {
        return flagService.toggleFlag(flagKey, environment, request.enabled());
    }

    @PostMapping("/{flagKey}/lifecycle")
    public LifecycleTransitionResponse transitionLifecycle(@PathVariable String flagKey,
                                                           @Valid @RequestBody LifecycleTransitionRequest request) {
        return lifecycleService.transitionFlag(flagKey, request);
    }

    @GetMapping("/{flagKey}/lifecycle/history")
    public List<LifecycleTransitionResponse> getTransitionHistory(@PathVariable String flagKey) {
        return lifecycleService.getTransitionHistory(flagKey);
    }
}
