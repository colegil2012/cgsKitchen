package com.celtech.solutions.cgsKitchen.services.storefront.kitchen;


import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Encodes the legal order-status transitions for kitchen and admin
 * actions. Pure utility — no dependencies, easy to unit test.
 *
 * <p><strong>Whitelist semantics:</strong> any transition not explicitly
 * allowed is rejected. The matrix is intentionally narrow:
 * <pre>
 *   PAID             → IN_KITCHEN, CANCELLED
 *   IN_KITCHEN       → READY, CANCELLED
 *   READY            → OUT_FOR_DELIVERY  (delivery only)
 *                    → COMPLETED         (pickup only)
 *                    → CANCELLED
 *   OUT_FOR_DELIVERY → COMPLETED, CANCELLED
 *   COMPLETED, CANCELLED, REFUNDED — terminal
 *   PENDING_PAYMENT — only the payment system transitions out
 * </pre>
 *
 * <p>{@link #isLegal} is the gate. Fulfillment-specific rules
 * (delivery vs pickup) are folded into the matrix by checking the order's
 * fulfillment type for the READY → next transitions.
 */
public final class OrderStateTransitions {

    private OrderStateTransitions() {}

    private static final Map<Order.Status, Set<Order.Status>> ALLOWED =
            new EnumMap<>(Order.Status.class);

    static {
        ALLOWED.put(Order.Status.PAID,             EnumSet.of(Order.Status.IN_KITCHEN, Order.Status.CANCELLED));
        ALLOWED.put(Order.Status.IN_KITCHEN,       EnumSet.of(Order.Status.READY, Order.Status.CANCELLED));
        ALLOWED.put(Order.Status.READY,            EnumSet.of(Order.Status.OUT_FOR_DELIVERY, Order.Status.COMPLETED, Order.Status.CANCELLED));
        ALLOWED.put(Order.Status.OUT_FOR_DELIVERY, EnumSet.of(Order.Status.COMPLETED, Order.Status.CANCELLED));
        ALLOWED.put(Order.Status.COMPLETED,        EnumSet.noneOf(Order.Status.class));
        ALLOWED.put(Order.Status.CANCELLED,        EnumSet.noneOf(Order.Status.class));
        ALLOWED.put(Order.Status.REFUNDED,         EnumSet.noneOf(Order.Status.class));
        ALLOWED.put(Order.Status.PENDING_PAYMENT,  EnumSet.noneOf(Order.Status.class));
    }

    /**
     * @return true if the transition is permitted given the order's
     *         fulfillment type.
     */
    public static boolean isLegal(Order order, Order.Status target) {
        Order.Status current = order.getStatus();
        if (current == target) return false;
        Set<Order.Status> allowed = ALLOWED.getOrDefault(current, EnumSet.noneOf(Order.Status.class));
        if (!allowed.contains(target)) return false;

        // Fulfillment-specific: from READY, the next state depends on
        // whether this is a delivery or pickup order.
        if (current == Order.Status.READY) {
            if (target == Order.Status.OUT_FOR_DELIVERY) {
                return order.getFulfillment() == Order.Fulfillment.DELIVERY;
            }
            if (target == Order.Status.COMPLETED) {
                return order.getFulfillment() == Order.Fulfillment.PICKUP
                        || order.getFulfillment() == Order.Fulfillment.DINE_IN;
            }
        }
        return true;
    }

    /**
     * Human-readable explanation for why a transition is rejected.
     * Returns null if the transition would be legal.
     */
    public static String rejectionReason(Order order, Order.Status target) {
        Order.Status current = order.getStatus();
        if (current == target) {
            return "Order is already in " + target;
        }
        Set<Order.Status> allowed = ALLOWED.getOrDefault(current, EnumSet.noneOf(Order.Status.class));
        if (allowed.isEmpty()) {
            return current + " is a terminal status; no transitions permitted";
        }
        if (!allowed.contains(target)) {
            return "Cannot transition from " + current + " to " + target
                    + "; allowed transitions are " + allowed;
        }
        if (current == Order.Status.READY) {
            if (target == Order.Status.OUT_FOR_DELIVERY
                    && order.getFulfillment() != Order.Fulfillment.DELIVERY) {
                return "OUT_FOR_DELIVERY is only valid for delivery orders; this order is "
                        + order.getFulfillment();
            }
            if (target == Order.Status.COMPLETED
                    && order.getFulfillment() == Order.Fulfillment.DELIVERY) {
                return "Delivery orders go through OUT_FOR_DELIVERY before COMPLETED";
            }
        }
        return null;
    }
}