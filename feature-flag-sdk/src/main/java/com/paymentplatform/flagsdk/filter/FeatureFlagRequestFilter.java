package com.paymentplatform.flagsdk.filter;

import com.paymentplatform.flagsdk.cache.FlagCacheManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Servlet filter that snapshots all flag values at request start into a ThreadLocal.
 * This ensures flag values remain consistent for the entire duration of a request,
 * preventing mid-transaction inconsistency — critical for payment processing.
 */
public class FeatureFlagRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagRequestFilter.class);
    private static final ThreadLocal<Map<String, Boolean>> PINNED_FLAGS = new ThreadLocal<>();

    private final FlagCacheManager cacheManager;

    public FeatureFlagRequestFilter(FlagCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            Map<String, Boolean> snapshot = cacheManager.getAllFlags();
            PINNED_FLAGS.set(snapshot);
            log.debug("Pinned {} flag values for request {}", snapshot.size(), request.getRequestURI());
            filterChain.doFilter(request, response);
        } finally {
            PINNED_FLAGS.remove();
        }
    }

    /**
     * Returns the pinned flag snapshot for the current request, or null if not in a request context.
     */
    public static Map<String, Boolean> getPinnedFlags() {
        return PINNED_FLAGS.get();
    }
}
