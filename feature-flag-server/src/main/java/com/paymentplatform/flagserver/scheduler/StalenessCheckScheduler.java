package com.paymentplatform.flagserver.scheduler;

import com.paymentplatform.flagserver.dto.LifecycleTransitionRequest;
import com.paymentplatform.flagserver.entity.FeatureFlag;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import com.paymentplatform.flagserver.repository.FeatureFlagRepository;
import com.paymentplatform.flagserver.repository.FlagAuditLogRepository;
import com.paymentplatform.flagserver.service.FlagAuditService;
import com.paymentplatform.flagserver.service.FlagLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class StalenessCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(StalenessCheckScheduler.class);

    private final FeatureFlagRepository flagRepository;
    private final FlagAuditLogRepository auditLogRepository;
    private final FlagAuditService auditService;
    private final FlagLifecycleService lifecycleService;
    private final boolean autoRetireEnabled;

    public StalenessCheckScheduler(
            FeatureFlagRepository flagRepository,
            FlagAuditLogRepository auditLogRepository,
            FlagAuditService auditService,
            FlagLifecycleService lifecycleService,
            @Value("${feature-flag.auto-retire-stale-flags:false}") boolean autoRetireEnabled) {
        this.flagRepository = flagRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.lifecycleService = lifecycleService;
        this.autoRetireEnabled = autoRetireEnabled;
    }

    @Scheduled(cron = "${feature-flag.staleness-check-cron:0 0 2 * * *}")
    @Transactional
    public void checkStaleness() {
        log.info("Running staleness check...");

        List<FeatureFlag> activeFlags = flagRepository.findByLifecycleState(LifecycleState.ACTIVE);
        int staleCount = 0;

        for (FeatureFlag flag : activeFlags) {
            if (isStale(flag)) {
                staleCount++;
                log.warn("Flag '{}' (owner: {}) is stale — active for more than {} days",
                        flag.getFlagKey(), flag.getOwner(), flag.getStaleAfterDays());

                if (autoRetireEnabled) {
                    lifecycleService.transitionFlag(flag.getFlagKey(),
                            new LifecycleTransitionRequest(
                                    LifecycleState.RETIRED.name(),
                                    "Auto-retired: flag stale for more than " + flag.getStaleAfterDays() + " days",
                                    "system"));
                    log.info("Auto-retired stale flag '{}' (was active for more than {} days)",
                            flag.getFlagKey(), flag.getStaleAfterDays());
                } else if (!auditLogRepository.existsByFlagIdAndAction(flag.getId(), FlagAction.STALE_WARNING)) {
                    auditService.logAction(
                            flag.getId(),
                            FlagAction.STALE_WARNING,
                            null,
                            null,
                            "system",
                            "Flag is stale: active for more than " + flag.getStaleAfterDays() + " days"
                    );
                }
            }
        }

        log.info("Staleness check complete. Found {} stale flag(s) out of {} active flag(s)",
                staleCount, activeFlags.size());
    }

    private boolean isStale(FeatureFlag flag) {
        if (flag.getStaleAfterDays() == null || flag.getStaleAfterDays() <= 0) {
            return false;
        }
        Instant staleThreshold = Instant.now().minus(flag.getStaleAfterDays(), ChronoUnit.DAYS);
        return flag.getUpdatedAt().isBefore(staleThreshold);
    }
}
