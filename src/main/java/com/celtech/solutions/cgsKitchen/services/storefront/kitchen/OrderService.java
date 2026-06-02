package com.celtech.solutions.cgsKitchen.services.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Order service — creates orders from cart line items, looks them up,
 * and refreshes an existing in-progress order when the cart changes.
 *
 * <p><b>API note:</b> {@link #createFromCart} and {@link #refreshFromCart}
 * previously took a {@code Cart} parameter (the session-scoped POJO).
 * After the cart layer was unified around the Mongo-backed {@link Cart}
 * document, the two methods take cart line items + subtotal directly.
 * Callers pull {@code cart.getLines()} and {@code cart.getSubtotalCents()}
 * from whichever cart they have. This decouples OrderService from the
 * cart container and avoids carrying around mutable cart state during
 * order creation.
 *
 * <p>Tax rate is currently a hardcoded 7%. In production, source from
 * the client's settings or use Stripe Tax for proper jurisdictional
 * lookup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final double TAX_RATE = 0.07;
    private static final Duration PENDING_TTL = Duration.ofDays(30);

    private final OrderRepository orders;
    private final KitchenQuoteService kitchenQuotes;

    /**
     * Create a PENDING_PAYMENT order from the current cart's line items.
     *
     * @param cartLines     the cart's line items (typically {@code cart.getLines()})
     * @param subtotalCents the cart's subtotal in cents (typically
     *                      {@code cart.getSubtotalCents()})
     */
    public Order createFromCart(List<Cart.CartLine> cartLines, long subtotalCents,
                                Order.Fulfillment fulfillment,
                                String userId,
                                String customerName, String customerEmail,
                                String customerPhone, String deliveryAddress,
                                long deliveryFeeCents,
                                String eventId) {
        long tax = Math.round(subtotalCents * TAX_RATE);
        long total = subtotalCents + tax + deliveryFeeCents;

        var lineItems = mapCartLines(cartLines);

        var order = Order.builder()
                .source(Order.Source.WEB)
                .status(Order.Status.PENDING_PAYMENT)
                .fulfillment(fulfillment)
                .userId(userId)
                .eventId(eventId)
                .customerName(customerName)
                .customerEmail(customerEmail)
                .customerPhone(customerPhone)
                .deliveryAddress(deliveryAddress)
                .items(lineItems)
                .subtotalCents(subtotalCents)
                .taxCents(tax)
                .deliveryFeeCents(deliveryFeeCents)
                .totalCents(total)
                .expiresAt(Instant.now().plus(PENDING_TTL))
                .promisedReadyAt(kitchenQuotes.waitForOrderItems(lineItems).readyAt())
                .build();

        return orders.save(order);
    }

    /**
     * Refresh a PENDING_PAYMENT order from the current cart + form state.
     * Used by the eager-mount flow to keep the Order in sync as the
     * visitor edits the checkout page (changes quantities, switches
     * fulfillment, gets a new delivery quote).
     *
     * <p>Refuses to mutate orders that have already left PENDING_PAYMENT.
     *
     * @param cartLines     the cart's line items (typically {@code cart.getLines()})
     * @param subtotalCents the cart's subtotal in cents (typically
     *                      {@code cart.getSubtotalCents()})
     */
    public Order refreshFromCart(Order order,
                                 List<Cart.CartLine> cartLines, long subtotalCents,
                                 Order.Fulfillment fulfillment,
                                 String customerName, String customerEmail,
                                 String customerPhone, String deliveryAddress,
                                 long deliveryFeeCents) {
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            log.warn("Refusing to refresh order {} in status {}",
                    order.getId(), order.getStatus());
            return order;
        }

        long tax = Math.round(subtotalCents * TAX_RATE);
        long total = subtotalCents + tax + deliveryFeeCents;

        order.setFulfillment(fulfillment);
        order.setCustomerName(customerName);
        order.setCustomerEmail(customerEmail);
        order.setCustomerPhone(customerPhone);
        order.setDeliveryAddress(deliveryAddress);
        order.setItems(mapCartLines(cartLines));
        order.setSubtotalCents(subtotalCents);
        order.setTaxCents(tax);
        order.setDeliveryFeeCents(deliveryFeeCents);
        order.setTotalCents(total);

        return orders.save(order);
    }

    /**
     * Map cart line items into the Order's embedded line items. Defensive
     * against null/empty inputs.
     */
    private List<Order.LineItem> mapCartLines(List<Cart.CartLine> cartLines) {
        if (cartLines == null) return List.of();
        return cartLines.stream()
                .map(l -> Order.LineItem.builder()
                        .menuItemId(l.getMenuItemId())
                        .name(l.getName())
                        .quantity(l.getQuantity())
                        .unitPriceCents(l.getUnitPriceCents())
                        .modifiers(l.getSelections().stream()
                                .map(s -> s.getGroupLabel() + ": " + s.getChoiceLabel())
                                .toList())
                        .build())
                .toList();
    }

    public Optional<Order> findById(String id) {
        return orders.findById(id);
    }

    public Optional<Order> findByCheckoutSessionId(String sessionId) {
        return orders.findByStripeCheckoutSessionId(sessionId);
    }

    public Optional<Order> findByPaymentIntentId(String paymentIntentId) {
        return orders.findByStripePaymentIntentId(paymentIntentId);
    }

    public Optional<Order> findByChargeId(String chargeId) {
        return orders.findByStripeChargeId(chargeId);
    }

    public Optional<Order> findByDeliveryExternalId(String deliveryExternalId) {
        return orders.findByDeliveryExternalId(deliveryExternalId);
    }

    public Page<Order> findAll(Pageable pageable) {
        return orders.findAll(pageable);
    }

    /**
     * All orders currently in flight — paid but not yet delivered/completed.
     * Used by the POS dashboard to show kitchen what to work on.
     */
    public List<Order> findActive() {
        return orders.findByStatusInOrderByCreatedAtAsc(java.util.List.of(
                Order.Status.PAID,
                Order.Status.IN_KITCHEN,
                Order.Status.READY,
                Order.Status.OUT_FOR_DELIVERY
        ));
    }

    /**
     * Abandoned-checkout sweeper helper: PENDING_PAYMENT orders older than
     * {@code cutoff}. Used by {@code AbandonedCheckoutSweeper} to cancel
     * stale Stripe PaymentIntents before their Mongo TTL fires.
     */
    public List<Order> findPendingPaymentOlderThan(java.time.Instant cutoff) {
        return orders.findByStatusAndCreatedAtBefore(Order.Status.PENDING_PAYMENT, cutoff);
    }

    public Order save(Order order) {
        return orders.save(order);
    }

    public Page<Order> findAdmin(Order.Status status, Pageable pageable) {
        if (status == null) {
            return orders.findAllByOrderByCreatedAtDesc(pageable);
        }
        return orders.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    public Page<Order> findAdminExcludingPending(Pageable pageable) {
        return orders.findByStatusNotOrderByCreatedAtDesc(
                Order.Status.PENDING_PAYMENT, pageable);
    }

    public List<Order> findByUserIdOrderByCreatedAtDesc(String userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Order updateStatus(String orderId, Order.Status newStatus) {
        Order o = orders.findById(orderId).orElseThrow();
        o.setStatus(newStatus);
        return orders.save(o);
    }
}