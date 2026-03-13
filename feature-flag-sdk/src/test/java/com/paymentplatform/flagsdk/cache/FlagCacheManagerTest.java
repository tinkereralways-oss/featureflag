package com.paymentplatform.flagsdk.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlagCacheManagerTest {

    private FlagCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new FlagCacheManager();
    }

    @Test
    void isEnabled_returnsFalse_whenFlagNotInCache() {
        assertFalse(cacheManager.isEnabled("unknown-flag", "prod"));
    }

    @Test
    void updateFlag_and_isEnabled() {
        cacheManager.updateFlag("my-flag", "prod", true);
        assertTrue(cacheManager.isEnabled("my-flag", "prod"));

        cacheManager.updateFlag("my-flag", "prod", false);
        assertFalse(cacheManager.isEnabled("my-flag", "prod"));
    }

    @Test
    void loadAll_populatesCache() {
        Map<String, Map<String, Boolean>> flags = Map.of(
                "flag-a", Map.of("prod", true, "staging", false),
                "flag-b", Map.of("prod", false)
        );

        cacheManager.loadAll(flags);

        assertTrue(cacheManager.isEnabled("flag-a", "prod"));
        assertFalse(cacheManager.isEnabled("flag-a", "staging"));
        assertFalse(cacheManager.isEnabled("flag-b", "prod"));
    }

    @Test
    void differentEnvironments_areIndependent() {
        cacheManager.updateFlag("my-flag", "prod", true);
        cacheManager.updateFlag("my-flag", "staging", false);

        assertTrue(cacheManager.isEnabled("my-flag", "prod"));
        assertFalse(cacheManager.isEnabled("my-flag", "staging"));
    }

    @Test
    void pendingState_storePrepareCommit() {
        cacheManager.updateFlag("my-flag", "prod", false);
        assertFalse(cacheManager.isEnabled("my-flag", "prod"));

        cacheManager.storePending("act-1", "my-flag", "prod", true);
        // Active cache should still show false
        assertFalse(cacheManager.isEnabled("my-flag", "prod"));

        cacheManager.commitPending("act-1", "my-flag", "prod");
        assertTrue(cacheManager.isEnabled("my-flag", "prod"));
    }

    @Test
    void pendingState_storePrepareRollback() {
        cacheManager.updateFlag("my-flag", "prod", false);

        cacheManager.storePending("act-1", "my-flag", "prod", true);
        cacheManager.rollbackPending("act-1", "my-flag", "prod");

        // Should remain false after rollback
        assertFalse(cacheManager.isEnabled("my-flag", "prod"));
    }

    @Test
    void commitPending_withNoPendingState_doesNotCrash() {
        cacheManager.commitPending("unknown-act", "my-flag", "prod");
        // Should not throw, just log warning
    }

    @Test
    void getAllFlags_returnsSnapshot() {
        cacheManager.updateFlag("flag-a", "prod", true);
        cacheManager.updateFlag("flag-b", "staging", false);

        Map<String, Boolean> all = cacheManager.getAllFlags();
        assertEquals(2, all.size());
        assertTrue(all.get("flag-a:prod"));
        assertFalse(all.get("flag-b:staging"));
    }
}
