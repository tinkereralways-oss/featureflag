package com.paymentplatform.flagserver.controller;

import com.paymentplatform.flagserver.dto.ActivationResponse;
import com.paymentplatform.flagserver.dto.AuditLogResponse;
import com.paymentplatform.flagserver.dto.CreateFlagRequest;
import com.paymentplatform.flagserver.dto.FlagResponse;
import com.paymentplatform.flagserver.dto.InstanceStatusResponse;
import com.paymentplatform.flagserver.dto.LifecycleTransitionRequest;
import com.paymentplatform.flagserver.dto.LifecycleTransitionResponse;
import com.paymentplatform.flagserver.dto.UpdateFlagRequest;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import com.paymentplatform.flagserver.service.FlagAuditService;
import com.paymentplatform.flagserver.service.FlagLifecycleService;
import com.paymentplatform.flagserver.service.FlagService;
import com.paymentplatform.flagserver.service.InstanceRegistryService;
import com.paymentplatform.flagserver.service.TwoPhaseActivationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private final FlagService flagService;
    private final FlagLifecycleService lifecycleService;
    private final InstanceRegistryService instanceRegistryService;
    private final TwoPhaseActivationService activationService;
    private final FlagAuditService auditService;

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    public DashboardController(FlagService flagService,
                               FlagLifecycleService lifecycleService,
                               InstanceRegistryService instanceRegistryService,
                               TwoPhaseActivationService activationService,
                               FlagAuditService auditService) {
        this.flagService = flagService;
        this.lifecycleService = lifecycleService;
        this.instanceRegistryService = instanceRegistryService;
        this.activationService = activationService;
        this.auditService = auditService;
    }

    @GetMapping
    public String flagList(@RequestParam(required = false) String lifecycle,
                           @RequestParam(required = false) String search,
                           Model model) {
        List<FlagResponse> flags = flagService.listFlags();

        if (lifecycle != null && !lifecycle.isBlank()) {
            flags = flags.stream()
                    .filter(f -> f.lifecycleState().equalsIgnoreCase(lifecycle))
                    .toList();
        }
        if (search != null && !search.isBlank()) {
            String query = search.toLowerCase();
            flags = flags.stream()
                    .filter(f -> f.flagKey().toLowerCase().contains(query)
                            || f.name().toLowerCase().contains(query)
                            || (f.owner() != null && f.owner().toLowerCase().contains(query)))
                    .toList();
        }

        model.addAttribute("flags", flags);
        model.addAttribute("lifecycleStates", LifecycleState.values());
        model.addAttribute("selectedLifecycle", lifecycle);
        model.addAttribute("search", search);
        return "flags/list";
    }

    @GetMapping("/flags/new")
    public String createFlagForm(Model model) {
        return "flags/create";
    }

    @PostMapping("/flags")
    public String createFlag(@RequestParam String flagKey,
                             @RequestParam String name,
                             @RequestParam(required = false) String description,
                             @RequestParam(required = false) String owner,
                             @RequestParam(required = false) Integer staleAfterDays,
                             @RequestParam(required = false) String environments,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        // Preserve form values for re-render on error
        model.addAttribute("formFlagKey", flagKey);
        model.addAttribute("formName", name);
        model.addAttribute("formDescription", description);
        model.addAttribute("formOwner", owner);
        model.addAttribute("formStaleAfterDays", staleAfterDays);
        model.addAttribute("formEnvironments", environments);

        // Validate before constructing the request
        if (flagKey == null || flagKey.isBlank()) {
            model.addAttribute("validationErrors", List.of("Flag key must not be blank"));
            return "flags/create";
        }
        if (!flagKey.matches("^[a-z0-9-]+$")) {
            model.addAttribute("validationErrors",
                    List.of("Flag key must contain only lowercase letters, numbers, and hyphens"));
            return "flags/create";
        }
        if (name == null || name.isBlank()) {
            model.addAttribute("validationErrors", List.of("Name must not be blank"));
            return "flags/create";
        }

        // Parse comma-separated environments into a list
        List<String> envList = List.of("default");
        if (environments != null && !environments.isBlank()) {
            envList = Arrays.stream(environments.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        CreateFlagRequest request = new CreateFlagRequest(
                flagKey, name, description, owner,
                staleAfterDays != null ? staleAfterDays : 90,
                envList);

        try {
            FlagResponse created = flagService.createFlag(request);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Flag '" + created.flagKey() + "' created successfully.");
            return "redirect:/dashboard/flags/" + created.flagKey();
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Failed to create flag: " + e.getMessage());
            return "flags/create";
        }
    }

    @GetMapping("/flags/{flagKey}/edit")
    public String editFlagForm(@PathVariable String flagKey, Model model) {
        FlagResponse flag = flagService.getFlag(flagKey);
        model.addAttribute("flag", flag);
        return "flags/edit";
    }

    @PostMapping("/flags/{flagKey}/edit")
    public String editFlag(@PathVariable String flagKey,
                           UpdateFlagRequest request,
                           RedirectAttributes redirectAttributes) {
        try {
            flagService.updateFlag(flagKey, request);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Flag '" + flagKey + "' updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to update flag: " + e.getMessage());
        }
        return "redirect:/dashboard/flags/" + flagKey;
    }

    @PostMapping("/flags/{flagKey}/lifecycle")
    public String transitionLifecycle(@PathVariable String flagKey,
                                      @RequestParam String targetState,
                                      @RequestParam(required = false) String reason,
                                      RedirectAttributes redirectAttributes) {
        try {
            LifecycleTransitionRequest request = new LifecycleTransitionRequest(
                    targetState, reason, "dashboard-user");
            lifecycleService.transitionFlag(flagKey, request);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Flag '" + flagKey + "' transitioned to " + targetState + " successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to transition flag: " + e.getMessage());
        }
        return "redirect:/dashboard/flags/" + flagKey;
    }

    @GetMapping("/flags/{flagKey}")
    public String flagDetail(@PathVariable String flagKey, Model model) {
        FlagResponse flag = flagService.getFlag(flagKey);
        List<LifecycleTransitionResponse> history = lifecycleService.getTransitionHistory(flagKey);

        model.addAttribute("flag", flag);
        model.addAttribute("transitionHistory", history);
        model.addAttribute("lifecycleStates", LifecycleState.values());
        return "flags/detail";
    }

    @GetMapping("/instances")
    public String instanceList(Model model) {
        List<InstanceStatusResponse> instances = instanceRegistryService.getAllInstances();
        long healthyCount = instances.stream()
                .filter(i -> HealthStatus.HEALTHY.name().equals(i.healthStatus()))
                .count();

        model.addAttribute("instances", instances);
        model.addAttribute("healthyCount", healthyCount);
        model.addAttribute("totalCount", instances.size());
        return "instances/list";
    }

    @GetMapping("/activations")
    public String activationList(Model model) {
        List<ActivationResponse> pending = activationService.getPendingActivations();
        model.addAttribute("activations", pending);
        return "activations/pending";
    }

    @GetMapping("/activations/{activationId}")
    public String activationDetail(@PathVariable String activationId, Model model) {
        ActivationResponse activation = activationService.getActivation(activationId);
        model.addAttribute("activation", activation);
        return "activations/detail";
    }

    @GetMapping("/audit")
    public String auditLog(@RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "25") int size,
                           Model model) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogResponse> auditPage = auditService.getAuditLog(pageable);

        model.addAttribute("auditEntries", auditPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", auditPage.getTotalPages());
        model.addAttribute("totalElements", auditPage.getTotalElements());
        return "audit/log";
    }

    @PostMapping("/flags/{flagKey}/environments/{environment}/toggle")
    public String toggleEnvironment(@PathVariable String flagKey,
                                     @PathVariable String environment,
                                     @RequestParam boolean enabled,
                                     RedirectAttributes redirectAttributes) {
        try {
            ActivationResponse response = flagService.toggleFlag(flagKey, environment, enabled);
            String stateLabel = enabled ? "ON" : "OFF";
            if ("PREPARING".equals(response.status())) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Toggle to " + stateLabel + " initiated for '" + environment
                                + "'. Two-phase activation in progress (" + response.totalInstances() + " instance(s)).");
            } else {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Environment '" + environment + "' toggled " + stateLabel + " successfully.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to toggle environment: " + e.getMessage());
        }
        return "redirect:/dashboard/flags/" + flagKey;
    }

    @PostMapping("/flags/{flagKey}/environments")
    public String addEnvironment(@PathVariable String flagKey,
                                  @RequestParam String environment,
                                  RedirectAttributes redirectAttributes) {
        try {
            flagService.addEnvironment(flagKey, environment);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Environment '" + environment + "' added successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to add environment: " + e.getMessage());
        }
        return "redirect:/dashboard/flags/" + flagKey;
    }

    @PostMapping("/flags/{flagKey}/delete")
    public String deleteFlag(@PathVariable String flagKey,
                              RedirectAttributes redirectAttributes) {
        try {
            LifecycleTransitionRequest request = new LifecycleTransitionRequest(
                    "ARCHIVED", "Deleted via dashboard", "dashboard-user");
            lifecycleService.transitionFlag(flagKey, request);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Flag '" + flagKey + "' has been archived.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to archive flag: " + e.getMessage());
        }
        return "redirect:/dashboard";
    }
}
