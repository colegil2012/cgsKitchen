package com.celtech.solutions.cgsKitchen.services.webhooks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link WebhookHandlerFailedEvent} by surfacing the failure
 * somewhere an operator will see it.
 *
 * <p><b>Dev behavior (current):</b> logs at ERROR with a distinct
 * {@code [WEBHOOK-ALERT]} marker. Point your log aggregator's alerting
 * rule at that marker and you have notifications without any external
 * integration.
 *
 * <p><b>Prod upgrade path:</b> replace the body of {@link #onFailure}
 * with a real channel — {@code JavaMailSender} to ops, a Slack incoming
 * webhook, PagerDuty events API, etc. The publishing side
 * ({@code StripeWebhookController}) and the event record don't change.
 *
 * <p>Runs {@code @Async} so a slow alert channel never blocks the
 * webhook response. Requires {@code @EnableAsync} (already present on
 * the application class).
 */
@Slf4j
@Component
public class WebhookFailureAlerter {

    @Async
    @EventListener
    public void onFailure(WebhookHandlerFailedEvent ev) {
        // The marker prefix is what an external alerting rule keys on.
        log.error("[WEBHOOK-ALERT] {} event {} ({}) failed: {}",
                ev.provider(), ev.eventId(), ev.eventType(), ev.reason());

        // --- Prod: replace/augment the above with e.g. ---
        // mailSender.send(buildOpsAlertEmail(ev));
        // slackClient.postIncoming(buildSlackPayload(ev));
    }
}