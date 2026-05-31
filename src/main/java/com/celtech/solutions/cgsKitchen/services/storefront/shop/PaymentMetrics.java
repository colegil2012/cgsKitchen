package com.celtech.solutions.cgsKitchen.services.storefront.shop;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for the payment lifecycle.
 *
 * <p>Exposes the following on {@code /actuator/prometheus} (under the
 * naming convention {@code payments_…_total}):
 * <ul>
 *   <li>{@code payments_created_total{client_id=…}} — increments when a
 *       Stripe PaymentIntent is created (lazy init or POS terminal).</li>
 *   <li>{@code payments_updated_total{client_id=…}} — every successful
 *       PaymentIntent update (amount/setup_future_usage changes).</li>
 *   <li>{@code payments_cancelled_total{client_id=…, reason=…}} — sweeper
 *       cancels stale intents; {@code reason} is "sweeper" today, but
 *       split out so future ad-hoc admin cancel paths can tag distinctly.</li>
 *   <li>{@code payments_succeeded_total{client_id=…}} — webhook reports
 *       {@code payment_intent.succeeded}.</li>
 *   <li>{@code payments_refunded_total{client_id=…, full=true|false}}
 *       — webhook reports a charge refund; the {@code full} tag captures
 *       whether the refund covered the full order total.</li>
 *   <li>{@code webhook_events_total{provider=…, type=…, outcome=…}} —
 *       every Stripe (and later Uber) webhook touch. {@code outcome} is
 *       "duplicate", "processed", or "failed".</li>
 * </ul>
 *
 * <p>Counter instances are cached per tag combination by Micrometer,
 * so calling {@code increment(...)} in a hot path is allocation-free
 * after the first invocation.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final String clientId;

    public PaymentMetrics(MeterRegistry registry, AppProperties props) {
        this.registry = registry;
        // Tag every metric with the deployed client so a future shared
        // metrics stack can split per tenant cleanly.
        this.clientId = props.clientId() == null ? "unset" : props.clientId();
    }

    public void paymentCreated() {
        counter("payments_created_total").increment();
    }

    public void paymentUpdated() {
        counter("payments_updated_total").increment();
    }

    public void paymentCancelled(String reason) {
        Counter.builder("payments_cancelled_total")
                .tag("client_id", clientId)
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    public void paymentSucceeded() {
        counter("payments_succeeded_total").increment();
    }

    public void paymentRefunded(boolean fullRefund) {
        Counter.builder("payments_refunded_total")
                .tag("client_id", clientId)
                .tag("full", String.valueOf(fullRefund))
                .register(registry)
                .increment();
    }

    public void webhookEvent(String provider, String type, String outcome) {
        Counter.builder("webhook_events_total")
                .tag("client_id", clientId)
                .tag("provider", provider == null ? "unknown" : provider)
                .tag("type", type == null ? "unknown" : type)
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    private Counter counter(String name) {
        return Counter.builder(name)
                .tag("client_id", clientId)
                .register(registry);
    }
}