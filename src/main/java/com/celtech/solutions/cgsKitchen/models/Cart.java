package com.celtech.solutions.cgsKitchen.models;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-scoped cart used by the Thymeleaf storefront.
 *
 * <p>Stored in the HTTP session (see CartConfig) so the browser doesn't
 * need to manage state. POS terminals don't use this — they POST orders
 * directly via /api/pos/orders.
 *
 * <p>Cart only knows about line items. Tax, delivery fee, and tip are
 * computed at checkout time from current settings.
 */
@Data
@NoArgsConstructor
public class Cart implements Serializable {

    private List<CartLine> lines = new ArrayList<>();

    public void add(MenuItem item, int qty) {
        if (qty <= 0) return;
        for (CartLine existing : lines) {
            if (existing.getMenuItemId().equals(item.getId())) {
                existing.setQuantity(existing.getQuantity() + qty);
                return;
            }
        }
        lines.add(new CartLine(
                item.getId(),
                item.getName(),
                item.getPriceCents(),
                qty
        ));
    }

    public void updateQuantity(String menuItemId, int qty) {
        if (qty <= 0) {
            remove(menuItemId);
            return;
        }
        for (CartLine line : lines) {
            if (line.getMenuItemId().equals(menuItemId)) {
                line.setQuantity(qty);
                return;
            }
        }
    }

    public void remove(String menuItemId) {
        lines.removeIf(l -> l.getMenuItemId().equals(menuItemId));
    }

    public void clear() {
        lines.clear();
    }

    public int getItemCount() {
        return lines.stream().mapToInt(CartLine::getQuantity).sum();
    }

    public long getSubtotalCents() {
        return lines.stream().mapToLong(CartLine::getLineTotalCents).sum();
    }

    public String getSubtotalDisplay() {
        return String.format("$%.2f", getSubtotalCents() / 100.0);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Data
    @NoArgsConstructor
    public static class CartLine implements Serializable {
        private String menuItemId;
        private String name;
        private long unitPriceCents;
        private int quantity;

        public CartLine(String menuItemId, String name, long unitPriceCents, int quantity) {
            this.menuItemId = menuItemId;
            this.name = name;
            this.unitPriceCents = unitPriceCents;
            this.quantity = quantity;
        }

        public long getLineTotalCents() {
            return unitPriceCents * quantity;
        }

        public String getUnitPriceDisplay() {
            return String.format("$%.2f", unitPriceCents / 100.0);
        }

        public String getLineTotalDisplay() {
            return String.format("$%.2f", getLineTotalCents() / 100.0);
        }
    }
}
