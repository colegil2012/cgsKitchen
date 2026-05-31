package com.celtech.solutions.cgsKitchen.delivery.uber;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.delivery.DeliveryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link DeliveryProvider} backed by Uber Direct.
 *
 * <p><strong>Quote ID handling:</strong> the domain's {@code DeliveryQuote}
 * carries our internal quote identifier ({@code q_<uuid>}), but Uber's
 * {@code createDelivery} call requires <em>their</em> quote id. We map the
 * two in {@link #quoteToUberId} so dispatch can look up the right Uber
 * quote_id. The map is in-memory and survives only for the app's lifetime —
 * acceptable for Uber's 15-minute quote validity window.
 *
 * <p>For multi-node prod this would move to Redis or store the Uber quote id
 * directly on the Order. For dev / single-node this is fine.
 */
@Slf4j
@RequiredArgsConstructor
public class UberDirectProvider implements DeliveryProvider {

    private final UberDirectClient client;
    private final AppProperties props;

    /** internal quote id (q_...)  ->  Uber quote id (dqt_...) */
    private final ConcurrentMap<String, String> quoteToUberId = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "uber";
    }

    @Override
    public DeliveryQuote quote(QuoteRequest request) {
        try {
            UberDirectClient.UberAddress pickup =
                    UberDirectClient.UberAddress.parseSingleLine(request.pickupAddress());
            UberDirectClient.UberAddress dropoff =
                    UberDirectClient.UberAddress.parseSingleLine(request.dropoffAddress());

            UberDirectClient.QuoteResponse uq = client.createQuote(pickup, dropoff);

            String internalQuoteId = "q_" + java.util.UUID.randomUUID();
            quoteToUberId.put(internalQuoteId, uq.id());

            log.info("Uber quote: internal={} uber={} fee={} duration={}m",
                    internalQuoteId, uq.id(), uq.feeCents(), uq.durationMinutes());

            return new DeliveryQuote(
                    internalQuoteId,
                    uq.feeCents(),
                    uq.durationMinutes(),
                    true
            );
        } catch (Exception e) {
            log.error("Uber quote failed", e);
            // Non-accepting quote so the UI can surface a friendly error.
            return new DeliveryQuote("q_failed_" + System.nanoTime(), 0, 0, false);
        }
    }

    @Override
    public DeliveryDispatch dispatch(DispatchRequest request) {
        // If we have a recent quote for this order, use it. Otherwise we need
        // to re-quote and immediately dispatch (rare path — e.g. dispatch hours
        // after the order was placed). We don't have the internal quote id on
        // DispatchRequest, so we re-quote here.
        try {
            UberDirectClient.UberAddress pickup =
                    UberDirectClient.UberAddress.parseSingleLine(request.pickupAddress());
            UberDirectClient.UberAddress dropoff =
                    UberDirectClient.UberAddress.parseSingleLine(request.dropoffAddress());

            UberDirectClient.QuoteResponse freshQuote = client.createQuote(pickup, dropoff);

            // Manifest: we don't have per-item detail on DispatchRequest, so
            // send the total as a single line item. Improve by extending
            // DispatchRequest later to carry the order line items.
            List<UberDirectClient.ManifestItem> manifest = List.of(
                    UberDirectClient.ManifestItem.simple(
                            "Order " + request.orderId(),
                            1,
                            (int) request.orderTotalCents())
            );

            UberDirectClient.CreateDeliveryRequest createReq =
                    new UberDirectClient.CreateDeliveryRequest(
                            freshQuote.id(),
                            pickup,
                            props.storefront().brandName(),
                            props.storefront().contactPhone(),
                            dropoff,
                            request.customerName(),
                            request.customerPhone(),
                            null,                       // dropoff_notes — wire up later if needed
                            manifest,
                            request.orderId(),
                            request.tipCents()
                    );

            UberDirectClient.DeliveryResponse d = client.createDelivery(createReq);

            log.info("Uber delivery dispatched: id={} status={} tracking={}",
                    d.id(), d.status(), d.trackingUrl());

            return new DeliveryDispatch(d.id(), d.trackingUrl(), d.status());

        } catch (Exception e) {
            log.error("Uber dispatch failed for order {}", request.orderId(), e);
            throw new RuntimeException("Uber Direct dispatch failed: " + e.getMessage(), e);
        }
    }
}