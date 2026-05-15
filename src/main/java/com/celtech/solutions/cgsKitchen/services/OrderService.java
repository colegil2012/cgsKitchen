package com.celtech.solutions.cgsKitchen.services;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.Cart;
import com.celtech.solutions.cgsKitchen.models.Order;
import com.celtech.solutions.cgsKitchen.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Order service — creates orders from a cart and looks them up.
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

    private final OrderRepository orders;
    private final AppProperties props;

    /**
     * Create a PENDING_PAYMENT order from the current cart. The order
     * is persisted so it survives the redirect-to-Stripe flow.
     */
    public Order createFromCart(Cart cart, Order.Fulfillment fulfillment,
                                String customerName, String customerEmail,
                                String customerPhone, String deliveryAddress,
                                long deliveryFeeCents) {
        long subtotal = cart.getSubtotalCents();
        long tax = Math.round(subtotal * TAX_RATE);
        long total = subtotal + tax + deliveryFeeCents;

        var lineItems = cart.getLines().stream()
                .map(l -> Order.LineItem.builder()
                        .menuItemId(l.getMenuItemId())
                        .name(l.getName())
                        .quantity(l.getQuantity())
                        .unitPriceCents(l.getUnitPriceCents())
                        .build())
                .toList();

        var order = Order.builder()
                .clientId(props.clientId())
                .source(Order.Source.WEB)
                .status(Order.Status.PENDING_PAYMENT)
                .fulfillment(fulfillment)
                .customerName(customerName)
                .customerEmail(customerEmail)
                .customerPhone(customerPhone)
                .deliveryAddress(deliveryAddress)
                .items(lineItems)
                .subtotalCents(subtotal)
                .taxCents(tax)
                .deliveryFeeCents(deliveryFeeCents)
                .totalCents(total)
                .build();

        return orders.save(order);
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

    public Page<Order> findAll(Pageable pageable) {
        return orders.findByClientId(props.clientId(), pageable);
    }

    public List<Order> findActive() {
        // Orders that the kitchen / staff care about — not done, not cancelled
        return orders.findByClientIdAndStatus(props.clientId(), Order.Status.PAID);
    }

    public Order save(Order order) {
        return orders.save(order);
    }
}
