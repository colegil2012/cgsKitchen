package com.celtech.solutions.cgsKitchen.services.webhooks;

import com.celtech.solutions.cgsKitchen.models.webhooks.WebhookEvent;
import com.celtech.solutions.cgsKitchen.repositories.webhooks.WebhookEventRepository;
import com.mongodb.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Dedup + audit log for inbound webhooks.
 *
 * <p>Webhook controllers call {@link #recordReceipt} at the top of every
 * handler. The unique compound index on {@code (provider, eventId)} makes
 * duplicate inserts fail at the database level — that's our dedup signal.
 *
 * <p>Race-safe: two concurrent threads inserting the same event will see
 * one succeed and one fail with a duplicate-key error. Both threads return
 * the existing record; only the first proceeds to the actual handler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventService {

    private final WebhookEventRepository repo;

    /**
     * Record receipt of a webhook event. Returns an {@link Outcome} that
     * tells the caller whether to process it (NEW) or skip (DUPLICATE).
     *
     * @param provider "stripe" or "uber"
     * @param eventId  the provider's event id
     * @param eventType e.g. "payment_intent.succeeded" or "event.delivery_status"
     * @param payload  raw request body
     */
    public Outcome recordReceipt(String provider, String eventId,
                                 String eventType, String payload) {
        if (eventId == null || eventId.isBlank()) {
            log.warn("Webhook from {} has no event id — cannot dedup", provider);
            return Outcome.unidentified();
        }

        // Pre-check (lets us return early without an insert attempt for
        // the common duplicate case — Stripe's retries arrive within seconds).
        Optional<WebhookEvent> existing = repo.findByProviderAndEventId(provider, eventId);
        if (existing.isPresent()) {
            log.info("Duplicate {} webhook {} ({}) — skipping",
                    provider, eventId, eventType);
            return Outcome.duplicate(existing.get());
        }

        // Attempt insert. Race with concurrent retry: one insert wins,
        // one throws DuplicateKey — the loser falls back to "duplicate".
        WebhookEvent fresh = WebhookEvent.builder()
                .provider(provider)
                .eventId(eventId)
                .eventType(eventType)
                .payload(payload)
                .receivedAt(Instant.now())
                .result("processing")
                .build();
        try {
            WebhookEvent saved = repo.save(fresh);
            return Outcome.newEvent(saved);
        } catch (DuplicateKeyException | DataIntegrityViolationException e) {
            log.info("Concurrent duplicate {} webhook {} — skipping",
                    provider, eventId);
            return repo.findByProviderAndEventId(provider, eventId)
                    .map(Outcome::duplicate)
                    .orElseGet(Outcome::unidentified);
        }
    }

    /** Mark a previously-recorded event as successfully processed. */
    public void markProcessed(WebhookEvent event) {
        event.setResult("processed");
        event.setProcessedAt(Instant.now());
        repo.save(event);
    }

    /** Mark a previously-recorded event as failed; reason is free-form. */
    public void markFailed(WebhookEvent event, String reason) {
        event.setResult("failed");
        event.setResultDetail(reason);
        event.setProcessedAt(Instant.now());
        repo.save(event);
    }

    public Optional<WebhookEvent> findForReplay(String provider, String eventId) {
        return repo.findByProviderAndEventId(provider, eventId);
    }

    /**
     * The result of recording a webhook receipt.
     *
     * <p>{@link #isNew()} → process the event normally.
     * <p>{@link #isDuplicate()} → return 200 without re-processing.
     * <p>{@link #isUnidentified()} → no event id; caller decides what to do
     * (usually process, since we can't dedup anyway).
     */
    public record Outcome(WebhookEvent event, Kind kind) {
        public enum Kind { NEW, DUPLICATE, UNIDENTIFIED }

        public static Outcome newEvent(WebhookEvent e) { return new Outcome(e, Kind.NEW); }
        public static Outcome duplicate(WebhookEvent e) { return new Outcome(e, Kind.DUPLICATE); }
        public static Outcome unidentified() { return new Outcome(null, Kind.UNIDENTIFIED); }

        public boolean isNew() { return kind == Kind.NEW; }
        public boolean isDuplicate() { return kind == Kind.DUPLICATE; }
        public boolean isUnidentified() { return kind == Kind.UNIDENTIFIED; }
    }
}