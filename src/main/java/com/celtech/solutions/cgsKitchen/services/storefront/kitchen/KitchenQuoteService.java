package com.celtech.solutions.cgsKitchen.services.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.MenuItem;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.Category;
import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.MenuItemRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.CategoryRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes "when will the kitchen have an order ready" estimates using a
 * capacity-based scheduling model.
 *
 * <p><strong>Slot model:</strong> the kitchen has {@code app.kitchen.capacity}
 * concurrent slots. Each slot works one order at a time. The wait for a
 * new order is found by FIFO-assigning current active orders (PAID +
 * IN_KITCHEN) to slots and seeing when the earliest slot frees up, then
 * adding the new order's own cook duration on top.
 *
 * <p><strong>Order duration model (max + surcharge):</strong> an order's
 * cook time is:
 * <pre>
 *   duration = max(distinct item prep times)
 *            + perItemSurcharge × (totalUnits − 1)
 * </pre>
 * The longest single item sets the floor (the kitchen's bottleneck item),
 * and every additional unit beyond the first — whether a different item
 * or another of the same — adds {@code app.kitchen.per-item-surcharge-minutes}
 * to account for handling/assembly/plating. A single-item order is just
 * that item's prep time. This replaces the old pure-{@code max} model,
 * which made a 4-item order take the same time as a 1-item order.
 *
 * <p>The same formula applies to BOTH the candidate (new) order and the
 * active orders already in the queue, so the model is internally
 * consistent.
 *
 * <p><strong>Per-item prep time</strong> resolves as: explicit
 * {@code MenuItem.prepTimeMinutes} → category's
 * {@code defaultPrepTimeMinutes} → {@code app.kitchen.default-item-prep-minutes}
 * → 5 minutes.
 *
 * <p>This isn't perfect — real kitchens have cross-slot handoffs, batch
 * effects (shared fryer), and equipment-specific contention. For a single
 * food truck the slot + max-surcharge approximation is plenty accurate
 * and tunable via the surcharge knob.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KitchenQuoteService {

    private static final int ABSOLUTE_FALLBACK_MINUTES = 5;

    private final AppProperties props;
    private final OrderRepository orders;
    private final MenuItemRepository menuItems;
    private final CategoryRepository categories;

    // ================================================================
    //  Public API
    // ================================================================

    /**
     * The current wait for a new order — the number to show on
     * {@code /menu} pages. Reflects only the queue of active orders;
     * does not add a candidate order's own cook time (callers don't
     * know what's in it yet).
     */
    public PrepEta currentWait() {
        return computeEta(0);  // no candidate duration
    }

    /**
     * The wait for a specific new cart. Used at storefront-checkout page
     * load to show the customer the accurate ETA for *their* cart.
     */
    public PrepEta waitForCart(Cart cart) {
        if (cart == null || cart.getLines() == null || cart.getLines().isEmpty()) {
            return currentWait();
        }
        List<ItemSpec> specs = cart.getLines().stream()
                .map(line -> new ItemSpec(
                        resolvePrepMinutes(line.getMenuItemId()),
                        Math.max(1, line.getQuantity())))
                .toList();
        return computeEta(durationForItems(specs));
    }

    /**
     * Used by {@code OrderService} at order-creation time when the cart
     * is no longer available but the Order has its line items already.
     */
    public PrepEta waitForOrderItems(List<Order.LineItem> items) {
        if (items == null || items.isEmpty()) return currentWait();
        List<ItemSpec> specs = items.stream()
                .map(i -> new ItemSpec(
                        resolvePrepMinutes(i.getMenuItemId()),
                        Math.max(1, i.getQuantity())))
                .toList();
        return computeEta(durationForItems(specs));
    }

    // ================================================================
    //  Core scheduling math
    // ================================================================

    /**
     * @param candidateDurationMinutes the new order's own cook duration
     *                                 (already computed via
     *                                 {@link #durationForItems}). Zero
     *                                 means "just tell me when a slot
     *                                 opens" (the {@link #currentWait}
     *                                 case).
     */
    private PrepEta computeEta(int candidateDurationMinutes) {
        int capacity = Math.max(props.kitchen().capacity(), 1);
        Instant now = Instant.now();

        // Gather active orders (PAID + IN_KITCHEN). Sort by entry time so
        // FIFO assignment makes sense.
        List<Order> active = new ArrayList<>();
        active.addAll(orders.findByStatus(Order.Status.IN_KITCHEN));
        active.addAll(orders.findByStatus(Order.Status.PAID));
        active.sort(Comparator.comparing(Order::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // Resolve per-order remaining durations.
        List<Long> orderDurationsSeconds = active.stream()
                .map(o -> remainingDurationSeconds(o, now))
                .filter(d -> d > 0)
                .collect(Collectors.toCollection(ArrayList::new));

        // Simulate slot-fill: min-heap of slot "free-at" times (seconds-from-now).
        PriorityQueue<Long> slots = new PriorityQueue<>();
        for (int i = 0; i < capacity; i++) slots.add(0L);  // all slots free now

        for (Long durSec : orderDurationsSeconds) {
            long earliest = slots.poll();        // smallest free-at
            slots.add(earliest + durSec);         // re-enqueue with this order added
        }

        // The next free slot — when a new order COULD start cooking.
        long nextFreeSec = slots.poll();

        long candidateSeconds = Math.max(0, candidateDurationMinutes) * 60L;
        long totalWaitSec = nextFreeSec + candidateSeconds;
        Instant readyAt = now.plusSeconds(totalWaitSec);
        int waitMinutes = (int) Math.ceil(totalWaitSec / 60.0);

        log.debug("Kitchen quote: capacity={} activeOrders={} candidateDurationMin={} totalWaitMin={}",
                capacity, active.size(), candidateDurationMinutes, waitMinutes);

        return new PrepEta(waitMinutes, readyAt, active.size(), capacity);
    }

    /**
     * How many seconds remain on this order's cook time. For IN_KITCHEN
     * orders we estimate progress assuming work started at the order's
     * last update (the PAID→IN_KITCHEN transition stamps updatedAt).
     */
    private long remainingDurationSeconds(Order order, Instant now) {
        int totalMinutes = orderDurationMinutes(order);
        long totalSec = totalMinutes * 60L;

        if (order.getStatus() == Order.Status.PAID) {
            return totalSec;  // not started — full duration to go
        }

        // IN_KITCHEN — approximate elapsed since last update.
        Instant startedAt = order.getUpdatedAt() != null
                ? order.getUpdatedAt()
                : order.getCreatedAt();
        if (startedAt == null) return totalSec;

        long elapsedSec = Math.max(0, Duration.between(startedAt, now).getSeconds());
        return Math.max(0, totalSec - elapsedSec);
    }

    /**
     * Cook duration for an existing order, using the same max+surcharge
     * model as the candidate path so the queue simulation and the new
     * order's estimate agree.
     */
    private int orderDurationMinutes(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return resolvePrepMinutesFallback();
        }
        List<ItemSpec> specs = order.getItems().stream()
                .map(i -> new ItemSpec(
                        resolvePrepMinutes(i.getMenuItemId()),
                        Math.max(1, i.getQuantity())))
                .toList();
        return durationForItems(specs);
    }

    // ================================================================
    //  Duration model — max(prep) + surcharge × (totalUnits − 1)
    // ================================================================

    /**
     * The order-duration formula, shared by the candidate cart and the
     * active queue orders.
     *
     * <pre>
     *   duration = max(item prep times) + k × (totalUnits − 1)
     * </pre>
     *
     * where {@code k = app.kitchen.per-item-surcharge-minutes} and
     * {@code totalUnits} is the sum of quantities. A single unit yields
     * just {@code max}. Empty input yields the global fallback (defensive;
     * callers generally guard empties before calling).
     */
    private int durationForItems(List<ItemSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return resolvePrepMinutesFallback();
        }
        int maxPrep = specs.stream()
                .mapToInt(ItemSpec::prepMinutes)
                .max()
                .orElse(resolvePrepMinutesFallback());

        int totalUnits = specs.stream()
                .mapToInt(ItemSpec::quantity)
                .sum();

        int surcharge = perItemSurchargeMinutes() * Math.max(0, totalUnits - 1);
        int duration = maxPrep + surcharge;

        log.debug("durationForItems: maxPrep={} totalUnits={} surcharge={} → {} min",
                maxPrep, totalUnits, surcharge, duration);
        return duration;
    }

    private int perItemSurchargeMinutes() {
        // Tunable via app.kitchen.per-item-surcharge-minutes. Defaults to
        // 0 if unset/negative, which reduces to the old pure-max model —
        // safe fallback if the config isn't present yet.
        int k = props.kitchen().perItemSurchargeMinutes();
        return Math.max(0, k);
    }

    // ================================================================
    //  Per-item prep time resolution
    // ================================================================

    /**
     * Item override → category default → global fallback.
     */
    private int resolvePrepMinutes(String menuItemId) {
        if (menuItemId == null) return resolvePrepMinutesFallback();
        MenuItem item = menuItems.findById(menuItemId).orElse(null);
        if (item == null) return resolvePrepMinutesFallback();
        if (item.getPrepTimeMinutes() != null && item.getPrepTimeMinutes() > 0) {
            return item.getPrepTimeMinutes();
        }
        if (item.getCategoryId() != null) {
            Category cat = categories.findById(item.getCategoryId()).orElse(null);
            if (cat != null && cat.getDefaultPrepTimeMinutes() > 0) {
                return cat.getDefaultPrepTimeMinutes();
            }
        }
        return resolvePrepMinutesFallback();
    }

    private int resolvePrepMinutesFallback() {
        int configured = props.kitchen().defaultItemPrepMinutes();
        return configured > 0 ? configured : ABSOLUTE_FALLBACK_MINUTES;
    }

    // ================================================================
    //  Internal types
    // ================================================================

    /** A resolved (prep-minutes, quantity) pair feeding the duration model. */
    private record ItemSpec(int prepMinutes, int quantity) {}

    // ================================================================
    //  Result type
    // ================================================================

    /**
     * @param waitMinutes      rounded up to the nearest minute, for display
     * @param readyAt          exact instant for stamping {@code promisedReadyAt}
     * @param activeOrderCount how many orders are currently active (PAID + IN_KITCHEN)
     * @param capacity         configured kitchen capacity, included for context
     */
    public record PrepEta(
            int waitMinutes,
            Instant readyAt,
            int activeOrderCount,
            int capacity
    ) {
        public String displayMinutes() { return waitMinutes + " min"; }
    }
}