package com.paymentplatform.flagsdk.filter;

import com.paymentplatform.flagsdk.cache.FlagCacheManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class FeatureFlagRequestFilterTest {

    private FlagCacheManager cacheManager;

    @Mock
    private FilterChain filterChain;

    private FeatureFlagRequestFilter filter;

    @BeforeEach
    void setUp() {
        cacheManager = new FlagCacheManager();
        filter = new FeatureFlagRequestFilter(cacheManager);
    }

    @Test
    void pinnedFlags_availableDuringRequest() throws Exception {
        cacheManager.updateFlag("my-flag", "prod", true);

        AtomicReference<Map<String, Boolean>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(FeatureFlagRequestFilter.getPinnedFlags());
            return null;
        }).when(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );

        filter.doFilterInternal(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                filterChain
        );

        assertNotNull(captured.get());
        assertTrue(captured.get().get("my-flag:prod"));
    }

    @Test
    void pinnedFlags_clearedAfterRequest() throws Exception {
        cacheManager.updateFlag("my-flag", "prod", true);

        filter.doFilterInternal(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                filterChain
        );

        assertNull(FeatureFlagRequestFilter.getPinnedFlags());
    }

    @Test
    void pinnedFlags_notAffectedByMidRequestCacheChange() throws Exception {
        cacheManager.updateFlag("my-flag", "prod", true);

        AtomicReference<Boolean> pinnedDuringRequest = new AtomicReference<>();
        doAnswer(invocation -> {
            // Simulate cache change mid-request
            cacheManager.updateFlag("my-flag", "prod", false);
            // Pinned value should still be true
            Map<String, Boolean> pinned = FeatureFlagRequestFilter.getPinnedFlags();
            pinnedDuringRequest.set(pinned.get("my-flag:prod"));
            return null;
        }).when(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );

        filter.doFilterInternal(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                filterChain
        );

        assertTrue(pinnedDuringRequest.get());
    }

    @Test
    void pinnedFlags_clearedEvenOnException() throws Exception {
        doAnswer(invocation -> {
            throw new ServletException("test error");
        }).when(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );

        assertThrows(ServletException.class, () ->
                filter.doFilterInternal(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        filterChain
                )
        );

        assertNull(FeatureFlagRequestFilter.getPinnedFlags());
    }
}
