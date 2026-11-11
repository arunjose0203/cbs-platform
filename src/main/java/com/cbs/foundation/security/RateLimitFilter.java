package com.cbs.foundation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client request throttling, applied before authentication.
 *
 * <p>Two token buckets are kept per client address: one for all requests and a stricter one that is only
 * charged when a request ends in 401, so token guessing and broken clients are cut off long before the
 * general budget is spent. Health probes are never throttled. Limits are per application instance; the
 * client address is the servlet remote address, so deployments behind a proxy must enable trusted
 * forwarded-header handling (see deploy/staging/compose.yml).
 */
public final class RateLimitFilter extends OncePerRequestFilter {
    @ConfigurationProperties("cbs.rate-limit")
    public record Settings(Boolean enabled, Integer requestsPerMinute, Integer authenticationFailuresPerMinute,
            Integer maxClients) {
        public Settings {
            enabled = enabled == null || enabled;
            requestsPerMinute = requestsPerMinute == null ? 600 : requestsPerMinute;
            authenticationFailuresPerMinute = authenticationFailuresPerMinute == null ? 20 : authenticationFailuresPerMinute;
            maxClients = maxClients == null ? 100_000 : maxClients;
            if (requestsPerMinute < 1 || authenticationFailuresPerMinute < 1 || maxClients < 1) {
                throw new IllegalArgumentException("Rate limits must be positive");
            }
        }
    }

    private static final long MINUTE = 60_000_000_000L;
    private final Settings settings;
    private final LongSupplier nanos;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public RateLimitFilter(Settings settings) { this(settings, System::nanoTime); }

    RateLimitFilter(Settings settings, LongSupplier nanos) { this.settings = settings; this.nanos = nanos; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !settings.enabled() || path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = nanos.getAsLong();
        Client client = client(String.valueOf(request.getRemoteAddr()), now);
        long wait = client.admit(now, settings);
        if (wait > 0) {
            long seconds = Math.max(1, (wait + 999_999_999L) / 1_000_000_000L);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(seconds));
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too many requests\",\"status\":429,"
                    + "\"code\":\"RATE_LIMITED\"}");
            return;
        }
        chain.doFilter(request, response);
        if (response.getStatus() == 401) { client.failed(nanos.getAsLong(), settings); }
    }

    int trackedClients() { return clients.size(); }

    private Client client(String address, long now) {
        Client existing = clients.get(address);
        if (existing != null) { return existing; }
        if (clients.size() >= settings.maxClients()) {
            // Bounded memory: drop clients idle for over a minute, and as a last resort start afresh.
            clients.values().removeIf(c -> c.idleSince(now) > MINUTE);
            if (clients.size() >= settings.maxClients()) { clients.clear(); }
        }
        return clients.computeIfAbsent(address, a -> new Client(now, settings));
    }

    private static final class Client {
        private double requests;
        private double failures;
        private long updated;

        Client(long now, Settings settings) {
            requests = settings.requestsPerMinute();
            failures = settings.authenticationFailuresPerMinute();
            updated = now;
        }

        synchronized long idleSince(long now) { return now - updated; }

        /** Returns 0 when admitted, otherwise the nanoseconds until a request would be admitted. */
        synchronized long admit(long now, Settings settings) {
            refill(now, settings);
            if (failures < 1) { return nanosUntilOne(failures, settings.authenticationFailuresPerMinute()); }
            if (requests < 1) { return nanosUntilOne(requests, settings.requestsPerMinute()); }
            requests -= 1;
            return 0;
        }

        synchronized void failed(long now, Settings settings) {
            refill(now, settings);
            failures = Math.max(0, failures - 1);
        }

        private void refill(long now, Settings settings) {
            long elapsed = Math.max(0, now - updated);
            updated = now;
            requests = Math.min(settings.requestsPerMinute(), requests + (double) elapsed * settings.requestsPerMinute() / MINUTE);
            failures = Math.min(settings.authenticationFailuresPerMinute(),
                    failures + (double) elapsed * settings.authenticationFailuresPerMinute() / MINUTE);
        }

        private static long nanosUntilOne(double tokens, int perMinute) {
            return (long) Math.ceil((1 - tokens) * MINUTE / perMinute);
        }
    }
}
