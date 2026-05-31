package com.celtech.solutions.cgsKitchen.delivery.uber;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Predicate;

/**
 * Tiny retry-with-backoff utility for outbound calls to Uber.
 *
 * <p>Stripe's Java SDK has automatic retries built in for retryable errors
 * (configurable via {@code Stripe.setMaxNetworkRetries}). Uber's API
 * doesn't — we have to do this ourselves. Rather than pull in Spring Retry
 * as a dependency just for this, here's a minimal in-place helper.
 *
 * <p>Defaults: 3 attempts total, 200ms → 400ms → 800ms backoff. Caller
 * provides a predicate that decides which exceptions are retryable
 * (typically: server-side 5xx and 429 rate-limit responses, never 4xx
 * client errors which would retry the same broken request).
 */
@Slf4j
public final class RetryHelper {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 200;

    private RetryHelper() {}

    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Run {@code action} with retries on exceptions that match {@code retryable}.
     */
    public static <T> T withRetries(
            String operationName,
            ThrowingSupplier<T> action,
            Predicate<Throwable> retryable) throws Exception {
        return withRetries(operationName, action, retryable,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY_MS);
    }

    public static <T> T withRetries(
            String operationName,
            ThrowingSupplier<T> action,
            Predicate<Throwable> retryable,
            int maxAttempts,
            long initialDelayMs) throws Exception {
        Exception lastException = null;
        long delay = initialDelayMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 1) {
                    log.info("{}: retry attempt {}/{}", operationName, attempt, maxAttempts);
                }
                return action.get();
            } catch (Exception e) {
                lastException = e;
                if (attempt == maxAttempts || !retryable.test(e)) {
                    throw e;
                }
                log.warn("{}: attempt {} failed ({}), retrying in {}ms",
                        operationName, attempt, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(operationName + " interrupted", ie);
                }
                delay *= 2;
            }
        }
        throw lastException;  // unreachable but keeps compiler happy
    }

    /**
     * Standard predicate for Uber's retryable conditions: server-side
     * errors and rate-limit responses.
     */
    public static boolean isUberRetryable(Throwable t) {
        if (t instanceof UberDirectClient.UberDirectException e) {
            int code = e.statusCode();
            return code == 429 || (code >= 500 && code < 600);
        }
        // Network-level failures (timeout, connection reset) are retryable.
        if (t instanceof java.net.SocketTimeoutException) return true;
        if (t instanceof java.net.ConnectException) return true;
        if (t instanceof java.io.IOException
                && t.getMessage() != null
                && t.getMessage().toLowerCase().contains("timeout")) {
            return true;
        }
        return false;
    }
}