package com.celtech.solutions.cgsKitchen.services.storefront.event;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Aggregates all orders tied to an event into a reconciliation summary:
 * income (per-order list + totals by payment method) and items sold
 * (rolled up by base menu item, with a modifier-level breakdown beneath).
 *
 * <p><b>Committed-reality semantics:</b> this reads the DB, so orders still
 * sitting in a POS offline queue (not yet flushed) are NOT counted. That is
 * intentional — the summary is a consistent reconciliation view of what has
 * actually been recorded, not a live till. The POS UI labels it as such.
 *
 * <p><b>Income totals exclude CANCELLED and REFUNDED orders.</b> Those
 * orders still appear in the per-order list (marked), but don't contribute
 * to revenue or the items-sold counts — you didn't ultimately sell them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSummaryService {

    private final OrderRepository orders;

    /** Statuses that count as real revenue / real sales. */
    private static final Set<Order.Status> COUNTED = EnumSet.of(
            Order.Status.PAID,
            Order.Status.IN_KITCHEN,
            Order.Status.READY,
            Order.Status.OUT_FOR_DELIVERY,
            Order.Status.COMPLETED
    );

    public EventSummary summarize(String eventId) {
        List<Order> all = orders.findByEventId(eventId);

        List<OrderLine> orderLines = new ArrayList<>();
        long totalCents = 0, cashCents = 0, cardCents = 0, otherCents = 0;
        int countedOrders = 0;

        // item rollup: base menu item -> aggregate, with modifier sub-rollup
        Map<String, ItemAgg> itemAggs = new LinkedHashMap<>();

        // newest first for the income list
        all.sort(Comparator.comparing(
                Order::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        for (Order o : all) {
            boolean counted = o.getStatus() != null && COUNTED.contains(o.getStatus());
            String pm = o.getPaymentMethod() == null ? "UNPAID" : o.getPaymentMethod().name();

            orderLines.add(new OrderLine(
                    o.getId(),
                    o.getCustomerName(),
                    pm,
                    o.getFulfillment() == null ? null : o.getFulfillment().name(),
                    o.getStatus() == null ? null : o.getStatus().name(),
                    o.getTotalCents(),
                    counted
            ));

            if (!counted) continue;
            countedOrders++;
            totalCents += o.getTotalCents();
            switch (o.getPaymentMethod() == null ? Order.PaymentMethod.UNPAID : o.getPaymentMethod()) {
                case CASH -> cashCents += o.getTotalCents();
                case CARD -> cardCents += o.getTotalCents();
                case OTHER -> otherCents += o.getTotalCents();
                default -> { /* UNPAID counted order — unusual; leave out of method split */ }
            }

            // items-sold rollup (only for counted orders)
            if (o.getItems() != null) {
                for (Order.LineItem li : o.getItems()) {
                    String base = li.getName() == null ? "(unnamed)" : li.getName();
                    ItemAgg agg = itemAggs.computeIfAbsent(base, k -> new ItemAgg(base));
                    agg.quantity += li.getQuantity();
                    agg.revenueCents += li.getUnitPriceCents() * li.getQuantity();

                    // modifier-level variant key: name + sorted modifiers
                    String variantKey = base;
                    List<String> mods = li.getModifiers();
                    if (mods != null && !mods.isEmpty()) {
                        List<String> sorted = new ArrayList<>(mods);
                        Collections.sort(sorted);
                        variantKey = base + " — " + String.join(", ", sorted);
                    }
                    VariantAgg v = agg.variants.computeIfAbsent(
                            variantKey, k -> new VariantAgg(
                                    mods == null ? List.of() : mods));
                    v.quantity += li.getQuantity();
                    v.revenueCents += li.getUnitPriceCents() * li.getQuantity();
                }
            }
        }

        // build item views
        List<ItemSold> itemsSold = new ArrayList<>();
        for (ItemAgg agg : itemAggs.values()) {
            List<ItemVariant> variants = new ArrayList<>();
            for (VariantAgg v : agg.variants.values()) {
                variants.add(new ItemVariant(v.modifiers, v.quantity, v.revenueCents));
            }
            variants.sort(Comparator.comparingInt((ItemVariant iv) -> iv.quantity()).reversed());
            itemsSold.add(new ItemSold(agg.name, agg.quantity, agg.revenueCents, variants));
        }
        itemsSold.sort(Comparator.comparingInt((ItemSold i) -> i.quantity()).reversed());

        return new EventSummary(
                eventId,
                new Income(totalCents, cashCents, cardCents, otherCents,
                        countedOrders, all.size(), orderLines),
                itemsSold
        );
    }

    // ---- internal mutable aggregators ----

    private static final class ItemAgg {
        final String name;
        int quantity;
        long revenueCents;
        final Map<String, VariantAgg> variants = new LinkedHashMap<>();
        ItemAgg(String name) { this.name = name; }
    }

    private static final class VariantAgg {
        final List<String> modifiers;
        int quantity;
        long revenueCents;
        VariantAgg(List<String> modifiers) { this.modifiers = modifiers; }
    }

    // ---- DTOs (returned to the controller) ----

    public record EventSummary(
            String eventId,
            Income income,
            List<ItemSold> itemsSold
    ) {}

    public record Income(
            long totalCents,
            long cashCents,
            long cardCents,
            long otherCents,
            int countedOrders,
            int totalOrders,
            List<OrderLine> orders
    ) {}

    public record OrderLine(
            String orderId,
            String customerName,
            String paymentMethod,
            String fulfillment,
            String status,
            long totalCents,
            boolean countedInTotal
    ) {}

    public record ItemSold(
            String name,
            int quantity,
            long revenueCents,
            List<ItemVariant> variants
    ) {}

    public record ItemVariant(
            List<String> modifiers,
            int quantity,
            long revenueCents
    ) {}
}