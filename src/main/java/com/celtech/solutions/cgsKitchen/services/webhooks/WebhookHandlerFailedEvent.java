package com.celtech.solutions.cgsKitchen.services.webhooks;

/**
 * Published when a webhook handler fails and the event is recorded as
 * FAILED. Carries just enough to identify the event for triage without
 * the full payload (which is already persisted in the webhook_events
 * collection and retrievable by id).
 *
 * <p>Decoupled via Spring's {@code ApplicationEventPublisher} so the
 * alerting mechanism (log marker today; email/Slack later) can change
 * without touching the webhook controller or {@link WebhookEventService}.
 *
 * @param provider   the source, e.g. "stripe"
 * @param eventId    the provider's event id (e.g. "evt_…")
 * @param eventType  the provider's event type (e.g. "payment_intent.succeeded")
 * @param reason     the failure message captured at markFailed time
 */
public record WebhookHandlerFailedEvent(
        String provider,
        String eventId,
        String eventType,
        String reason
) {}