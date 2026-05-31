package com.celtech.solutions.cgsKitchen.services.webhooks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes work on a per-order basis. Two webhook events arriving at
 * essentially the same time for the same order — say, Stripe's
 * {@code payment_intent.succeeded} and {@code checkout.session.completed}
 * racing to mark the order PAID — would otherwise both read the same
 * "PENDING_PAYMENT" state, both transition to PAID, and both attempt the
 * dispatch flow.
 *
 * <p>Wrapping the order-mutating block in {@link #withLock} guarantees
 * sequential access keyed by order id. Locks are stored in a
 * ConcurrentHashMap; entries are evicted when there are no further holders
 * (the small footprint matters for long-running apps).
 *
 * <p>Single-node only. Multi-node would need a distributed lock
 * (Redisson, ShedLock, or similar). Documented in Phase 5+ deferred items.
 */
@Slf4j
@Service
public class OrderLockService {

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    /**
     * Run {@code action} while holding the lock for {@code orderId}. Lock
     * is acquired before invocation and released after, even on exception.
     */
    public <T> T withLock(String orderId, Supplier<T> action) {
        if (orderId == null || orderId.isBlank()) {
            // No id to key on — just run; the caller is reading global state.
            return action.get();
        }
        LockEntry entry = locks.compute(orderId, (k, existing) -> {
            if (existing == null) existing = new LockEntry();
            existing.refCount++;
            return existing;
        });
        entry.lock.lock();
        try {
            return action.get();
        } finally {
            entry.lock.unlock();
            locks.compute(orderId, (k, existing) -> {
                if (existing == null) return null;
                existing.refCount--;
                return existing.refCount <= 0 ? null : existing;
            });
        }
    }

    public void withLock(String orderId, Runnable action) {
        withLock(orderId, () -> { action.run(); return null; });
    }

    private static class LockEntry {
        final ReentrantLock lock = new ReentrantLock();
        int refCount = 0;
    }
}