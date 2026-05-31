package com.celtech.solutions.cgsKitchen.delivery.uber;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;

/**
 * Decides what an Uber delivery-status string means for our order.
 *
 * <p>The old version of this mapper conflated three things: it mapped
 * Uber statuses directly to Order.Status. That was wrong:
 * <ul>
 *   <li>Uber's {@code pending}, {@code pickup}, {@code pickup_complete},
 *       {@code dropoff} are not all the same event from our perspective.
 *       Only {@code pickup_complete} means "food has left our truck".</li>
 *   <li>Uber's {@code canceled} arriving mid-flight is not the same as
 *       "order cancelled" — the food is still ours. We flag the order
 *       for kitchen attention rather than auto-cancelling.</li>
 * </ul>
 *
 * <p>The new mapper returns a {@link Decision} describing what to do.
 * The handler reads the decision and acts.
 */
public final class UberDeliveryStatusMapper {

    private UberDeliveryStatusMapper() {}

    /**
     * What to do in response to an Uber status webhook.
     *
     * @param nextStatus            transition the order to this status, or null
     *                              to leave the order's status alone
     * @param markAttentionRequired set deliveryAttentionRequired=true so
     *                              kitchen is alerted
     * @param terminalFailure       true when this is a canceled/returned —
     *                              we should NOT poll this delivery anymore
     */
    public record Decision(
            Order.Status nextStatus,
            boolean markAttentionRequired,
            boolean terminalFailure
    ) {
        public static final Decision noop = new Decision(null, false, false);

        public boolean changesStatus() { return nextStatus != null; }
    }

    /**
     * @param uberStatus    the {@code status} field from an Uber webhook
     *                      or {@code GET /deliveries/{id}} response.
     * @param currentStatus the order's current status — used to avoid
     *                      no-op transitions and to apply context-aware rules.
     */
    public static Decision decide(String uberStatus, Order.Status currentStatus) {
        if (uberStatus == null) return Decision.noop;
        return switch (uberStatus.toLowerCase()) {

            // Courier being arranged / en route to pickup. The food is
            // still in our kitchen. No order status change — log only.
            case "pending", "pickup" -> Decision.noop;

            // Courier has the food. THIS is when the order goes
            // OUT_FOR_DELIVERY — regardless of whether we were in
            // PAID, IN_KITCHEN, or READY (kitchen may be running late).
            case "pickup_complete", "dropoff" -> {
                if (currentStatus == Order.Status.OUT_FOR_DELIVERY) {
                    yield Decision.noop;  // already there
                }
                yield new Decision(Order.Status.OUT_FOR_DELIVERY, false, false);
            }

            // Customer has the food.
            case "delivered" -> {
                if (currentStatus == Order.Status.COMPLETED) {
                    yield Decision.noop;
                }
                yield new Decision(Order.Status.COMPLETED, false, true);
            }

            // Delivery aborted. Do NOT cancel the order automatically —
            // food may be paid for and partly cooked. Flag for kitchen.
            case "canceled", "cancelled", "returned" ->
                    new Decision(null, true, true);

            default -> Decision.noop;
        };
    }

    /** True if the Uber status represents a terminal state — stop polling. */
    public static boolean isTerminal(String uberStatus) {
        if (uberStatus == null) return false;
        return switch (uberStatus.toLowerCase()) {
            case "delivered", "canceled", "cancelled", "returned" -> true;
            default -> false;
        };
    }
}