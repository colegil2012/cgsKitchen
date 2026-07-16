package com.celtech.solutions.cgsKitchen.controllers.api;

import com.celtech.solutions.cgsKitchen.config.properties.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.DeliveryEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderTransitionService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * POS-facing API for order state changes.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/orders/{id}/status} — transition</li>
 *   <li>{@code POST /api/orders/{id}/redispatch} — request new courier</li>
 *   <li>{@code GET  /api/orders/{id}} — fetch order</li>
 *   <li>{@code GET  /api/orders/{id}/events} — order audit log</li>
 *   <li>{@code GET  /api/orders/{id}/delivery-events} — provider telemetry</li>
 *   <li>{@code GET  /api/orders/active} — all in-flight orders</li>
 * </ul>
 *
 * <p>All mutating operations delegate to {@link OrderTransitionService}
 * — the same service the admin web controller uses. Behavior is
 * identical regardless of which surface called.
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class PosOrderController {

    private final OrderService orderService;
    private final EventService eventService;
    private final UserService userService;
    private final OrderTransitionService transitions;
    private final OrderEventService orderEvents;
    private final DeliveryEventService deliveryEvents;
    private final AppProperties props;


    // ------------------------------------------------------------------
    // POS — create an order from POS system
    // ------------------------------------------------------------------

    @PostMapping("/create")
    public ResponseEntity<?> createPosOrder(@Valid @RequestBody PosOrderRequest req) {
        // --- Event linkage guard: no orphaned orders. ---
        // We check EXISTENCE, not liveness. An offline cash order flushed
        // after its event ended is legitimate and must succeed; only a
        // missing/garbage eventId is rejected.
        if (req.eventId() == null || req.eventId().isBlank()) {
            return ResponseEntity.status(400).body(
                    new PosApiController.ErrorResponse("missing_event",
                            "Order must include an eventId. No event was active at ring-up."));
        }
        if (eventService.findById(req.eventId()).isEmpty()) {
            return ResponseEntity.status(400).body(
                    new PosApiController.ErrorResponse("unknown_event",
                            "eventId '" + req.eventId() + "' does not reference a known event."));
        }

        long subtotal = req.items().stream()
                .mapToLong(i -> i.unitPriceCents() * i.quantity())
                .sum();
        long tax = Math.round(subtotal * props.storefront().taxRate());
        long total = subtotal + tax;

        // If a registered user is attached, trust the account's email over
        // whatever the terminal echoed back (avoids typos / stale values).
        String customerEmail = req.customerEmail();
        String customerName = req.customerName();
        if (req.userId() != null && !req.userId().isBlank()) {
            var u = userService.findById(req.userId()).orElse(null);
            if (u != null) {
                customerEmail = u.getEmail();
                if (customerName == null || customerName.isBlank()) {
                    customerName = u.getDisplayName();
                }
            }
        }

        var order = Order.builder()
                .source(Order.Source.POS)
                .status(Order.Status.PENDING_PAYMENT)
                .paymentMethod(Order.PaymentMethod.UNPAID)
                .fulfillment(Order.Fulfillment.PICKUP)
                .eventId(req.eventId())
                .userId(req.userId())
                .customerName(customerName)
                .customerEmail(customerEmail)
                .items(req.items().stream()
                        .map(i -> Order.LineItem.builder()
                                .menuItemId(i.menuItemId())
                                .name(i.name())
                                .quantity(i.quantity())
                                .unitPriceCents(i.unitPriceCents())
                                .modifiers(i.modifiers() == null ? List.of() : i.modifiers())
                                .build())
                        .toList())
                .subtotalCents(subtotal)
                .taxCents(tax)
                .totalCents(total)
                .build();
        var saved = orderService.save(order);
        return ResponseEntity.created(URI.create("/api/orders/" + saved.getId()))
                .body(saved);
    }

    // ================================================================
    //  Mutations
    // ================================================================

    @PostMapping("/{orderId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable String orderId,
            @Valid @RequestBody UpdateStatusRequest req) {

        var result = transitions.transition(orderId, req.status(),
                "pos", req.actor(), req.note());

        return switch (result.outcome()) {
            case SUCCESS, NO_CHANGE -> ResponseEntity.ok(toView(result.order()));
            case REJECTED -> ResponseEntity.status(400)
                    .body(new ErrorResponse("invalid_transition", result.message()));
            case NOT_FOUND -> ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", result.message()));
        };
    }

    @PostMapping("/{orderId}/cash-payment")
    public ResponseEntity<?> confirmCash(
            @PathVariable String orderId,
            @RequestBody(required = false) CashPaymentRequest req) {

        String actor = req == null ? null : req.actor();
        String note  = req == null ? null : req.note();
        var result = transitions.confirmCashPayment(orderId, actor, note);

        return switch (result.outcome()) {
            case SUCCESS, NO_CHANGE -> ResponseEntity.ok(toView(result.order()));
            case REJECTED -> ResponseEntity.status(400)
                    .body(new ErrorResponse("invalid_state", result.message()));
            case NOT_FOUND -> ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", result.message()));
        };
    }

    public record CashPaymentRequest(String actor, String note) {}

    @PostMapping("/{orderId}/redispatch")
    public ResponseEntity<?> redispatch(
            @PathVariable String orderId,
            @RequestBody(required = false) RedispatchRequest req) {

        String actor = req == null ? null : req.actor();
        var result = transitions.redispatch(orderId, actor);

        return switch (result.outcome()) {
            case SUCCESS, NO_CHANGE -> ResponseEntity.ok(toView(result.order()));
            case REJECTED -> ResponseEntity.status(400)
                    .body(new ErrorResponse("invalid", result.message()));
            case NOT_FOUND -> ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", result.message()));
        };
    }

    // ================================================================
    //  Reads
    // ================================================================

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable String orderId) {
        return orderService.findById(orderId)
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(toView(o)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ErrorResponse("not_found", "Order " + orderId + " not found")));
    }

    @GetMapping("/{orderId}/events")
    public ResponseEntity<?> getEvents(@PathVariable String orderId) {
        if (orderService.findById(orderId).isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Order " + orderId + " not found"));
        }
        return ResponseEntity.ok(orderEvents.historyFor(orderId));
    }

    @GetMapping("/{orderId}/delivery-events")
    public ResponseEntity<?> getDeliveryEvents(@PathVariable String orderId) {
        if (orderService.findById(orderId).isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Order " + orderId + " not found"));
        }
        return ResponseEntity.ok(deliveryEvents.historyForOrder(orderId));
    }

    @GetMapping("/active")
    public ResponseEntity<List<OrderView>> activeOrders() {
        return ResponseEntity.ok(
                orderService.findActive().stream().map(this::toView).toList());
    }

    // ================================================================
    //  DTOs
    // ================================================================


    public record PosOrderRequest(
            @NotEmpty List<PosLineItem> items,
            String eventId,
            String userId,          // null for walk-in; set when attached to a user
            String customerName,    // denormalized for display
            String customerEmail
    ) {}

    public record PosLineItem(
            String menuItemId,
            String name,
            @Positive int quantity,
            @Positive long unitPriceCents,
            List<String> modifiers   // "Group: Choice" labels; may be null/empty
    ) {}

    public record UpdateStatusRequest(
            @NotNull Order.Status status,
            String actor,
            String note
    ) {}

    public record RedispatchRequest(String actor) {}

    public record ErrorResponse(String code, String message) {}

    public record OrderView(
            String id,
            Order.Status status,
            Order.Fulfillment fulfillment,
            String customerName,
            String customerPhone,
            String deliveryAddress,
            long totalCents,
            String deliveryProvider,
            String deliveryTrackingUrl,
            String cancellationReason,
            String cancellationDetail,
            boolean deliveryAttentionRequired,
            Instant createdAt,
            Instant updatedAt,
            List<Order.LineItem> items
    ) {}

    private OrderView toView(Order o) {
        return new OrderView(
                o.getId(),
                o.getStatus(),
                o.getFulfillment(),
                o.getCustomerName(),
                o.getCustomerPhone(),
                o.getDeliveryAddress(),
                o.getTotalCents(),
                o.getDeliveryProvider(),
                o.getDeliveryTrackingUrl(),
                o.getCancellationReason(),
                o.getCancellationDetail(),
                o.isDeliveryAttentionRequired(),
                o.getCreatedAt(),
                o.getUpdatedAt(),
                o.getItems()
        );
    }
}