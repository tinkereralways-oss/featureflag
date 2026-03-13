package com.paymentplatform.flagserver.controller;

import com.paymentplatform.flagserver.dto.ActivationResponse;
import com.paymentplatform.flagserver.dto.AuditLogResponse;
import com.paymentplatform.flagserver.dto.FlagEnvironmentResponse;
import com.paymentplatform.flagserver.dto.FlagResponse;
import com.paymentplatform.flagserver.dto.InstanceStatusResponse;
import com.paymentplatform.flagserver.dto.LifecycleTransitionResponse;
import com.paymentplatform.flagserver.service.FlagAuditService;
import com.paymentplatform.flagserver.service.FlagLifecycleService;
import com.paymentplatform.flagserver.service.FlagService;
import com.paymentplatform.flagserver.service.InstanceRegistryService;
import com.paymentplatform.flagserver.service.TwoPhaseActivationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private FlagService flagService;

    @Mock
    private FlagLifecycleService lifecycleService;

    @Mock
    private InstanceRegistryService instanceRegistryService;

    @Mock
    private TwoPhaseActivationService activationService;

    @Mock
    private FlagAuditService auditService;

    @InjectMocks
    private DashboardController controller;

    private FlagResponse buildFlag(String key, String name, String owner, String lifecycle) {
        return new FlagResponse("id-" + key, key, name, "desc", owner, lifecycle, 90,
                List.of(new FlagEnvironmentResponse(1L, "prod", true, 100)), Instant.now(), Instant.now());
    }

    @Test
    void flagList_returnsAllFlags() {
        Model model = new ConcurrentModel();
        when(flagService.listFlags()).thenReturn(List.of(
                buildFlag("flag-a", "Flag A", "team-a", "ACTIVE"),
                buildFlag("flag-b", "Flag B", "team-b", "CREATED")
        ));

        String view = controller.flagList(null, null, model);

        assertEquals("flags/list", view);
        List<?> flags = (List<?>) model.getAttribute("flags");
        assertEquals(2, flags.size());
    }

    @Test
    void flagList_filtersByLifecycle() {
        Model model = new ConcurrentModel();
        when(flagService.listFlags()).thenReturn(List.of(
                buildFlag("flag-a", "Flag A", "team-a", "ACTIVE"),
                buildFlag("flag-b", "Flag B", "team-b", "CREATED")
        ));

        String view = controller.flagList("ACTIVE", null, model);

        assertEquals("flags/list", view);
        List<?> flags = (List<?>) model.getAttribute("flags");
        assertEquals(1, flags.size());
    }

    @Test
    void flagList_filtersBySearch() {
        Model model = new ConcurrentModel();
        when(flagService.listFlags()).thenReturn(List.of(
                buildFlag("payment-flag", "Payment Flag", "team-pay", "ACTIVE"),
                buildFlag("checkout-flag", "Checkout Flag", "team-checkout", "ACTIVE")
        ));

        String view = controller.flagList(null, "payment", model);

        assertEquals("flags/list", view);
        List<?> flags = (List<?>) model.getAttribute("flags");
        assertEquals(1, flags.size());
    }

    @Test
    void flagDetail_returnsDetailView() {
        Model model = new ConcurrentModel();
        FlagResponse flag = buildFlag("my-flag", "My Flag", "owner", "ACTIVE");
        when(flagService.getFlag("my-flag")).thenReturn(flag);
        when(lifecycleService.getTransitionHistory("my-flag")).thenReturn(Collections.emptyList());

        String view = controller.flagDetail("my-flag", model);

        assertEquals("flags/detail", view);
        assertEquals(flag, model.getAttribute("flag"));
    }

    @Test
    void instanceList_returnsInstances() {
        Model model = new ConcurrentModel();
        when(instanceRegistryService.getAllInstances()).thenReturn(List.of(
                new InstanceStatusResponse("i-1", "svc-a", "host", 8080, "HEALTHY", "1.0", Instant.now(), Instant.now()),
                new InstanceStatusResponse("i-2", "svc-b", "host", 8081, "UNHEALTHY", "1.0", Instant.now(), Instant.now())
        ));

        String view = controller.instanceList(model);

        assertEquals("instances/list", view);
        assertEquals(1L, model.getAttribute("healthyCount"));
        assertEquals(2, model.getAttribute("totalCount"));
    }

    @Test
    void activationList_returnsPending() {
        Model model = new ConcurrentModel();
        when(activationService.getPendingActivations()).thenReturn(Collections.emptyList());

        String view = controller.activationList(model);

        assertEquals("activations/pending", view);
        List<?> activations = (List<?>) model.getAttribute("activations");
        assertTrue(activations.isEmpty());
    }

    @Test
    void activationDetail_returnsDetail() {
        Model model = new ConcurrentModel();
        ActivationResponse activation = new ActivationResponse(
                "act-1", "flag-1", "prod", true, "PREPARING", 3, 1, 60,
                List.of("i-1"), Instant.now(), null);
        when(activationService.getActivation("act-1")).thenReturn(activation);

        String view = controller.activationDetail("act-1", model);

        assertEquals("activations/detail", view);
        assertEquals(activation, model.getAttribute("activation"));
    }

    @Test
    void auditLog_returnsPaginated() {
        Model model = new ConcurrentModel();
        AuditLogResponse entry = new AuditLogResponse(
                1L, "flag-1", "CREATED", null, "my-flag", "admin", null, Instant.now());
        Page<AuditLogResponse> page = new PageImpl<>(List.of(entry), PageRequest.of(0, 25), 1);
        when(auditService.getAuditLog(any())).thenReturn(page);

        String view = controller.auditLog(0, 25, model);

        assertEquals("audit/log", view);
        assertEquals(0, model.getAttribute("currentPage"));
        assertEquals(1, model.getAttribute("totalPages"));
        assertEquals(1L, model.getAttribute("totalElements"));
    }
}
