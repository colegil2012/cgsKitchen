package com.celtech.solutions.cgsKitchen.services.storefront.shop;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.view.MenuItemView;
import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;
import com.celtech.solutions.cgsKitchen.repositories.storefront.shop.CartRepository;
import com.celtech.solutions.cgsKitchen.services.storefront.menu.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The single API for the cart layer.
 *
 * <p>Two flavors of cart, one logical entity:
 * <ul>
 *   <li><b>User cart</b> — owned by a signed-in user. Persists forever.
 *       Looked up by {@code userId}.</li>
 *   <li><b>Guest cart</b> — owned by a browser cookie. TTL-reaped after
 *       30 days of inactivity. Looked up by {@code cookieId}.</li>
 * </ul>
 *
 * <p>Mutation methods ({@link #addLine}, {@link #updateLineQuantity},
 * {@link #removeLine}) save the cart immediately — callers don't need
 * to remember to call {@link #save} after.
 *
 * <p>Invariant: at any moment a cart has exactly one of (userId, cookieId)
 * populated. When a guest signs in, {@link #mergeGuestIntoUser} promotes
 * the guest cart's contents into the user's cart and deletes the guest
 * cart row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    /** TTL for guest carts: 30 days from last touch. */
    public static final Duration GUEST_TTL = Duration.ofDays(30);

    private final CartRepository carts;
    private final MenuService menuService;

    // ================================================================
    //  Read / find-or-create
    // ================================================================

    public Optional<Cart> findByUser(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        return carts.findByUserId(userId);
    }

    public Optional<Cart> findByCookie(String cookieId) {
        if (cookieId == null || cookieId.isBlank()) return Optional.empty();
        return carts.findByCookieId(cookieId);
    }

    /**
     * Get the user's cart, creating an empty one if none exists.
     * Use this when rendering /checkout, /cart, etc. for a signed-in user.
     */
    public Cart findOrCreateForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required");
        }
        return carts.findByUserId(userId).orElseGet(() -> {
            Cart fresh = Cart.builder()
                    .userId(userId)
                    .lines(new ArrayList<>())
                    .build();
            Cart saved = carts.save(fresh);
            log.info("Created user cart {} for user {}", saved.getId(), userId);
            return saved;
        });
    }

    /**
     * Get the guest cart for a cookie id, creating an empty one if none
     * exists. The {@code expiresAt} TTL is set/refreshed on create.
     */
    public Cart findOrCreateForGuest(String cookieId) {
        if (cookieId == null || cookieId.isBlank()) {
            throw new IllegalArgumentException("cookieId required");
        }
        return carts.findByCookieId(cookieId).orElseGet(() -> {
            Cart fresh = Cart.builder()
                    .cookieId(cookieId)
                    .lines(new ArrayList<>())
                    .expiresAt(Instant.now().plus(GUEST_TTL))
                    .build();
            Cart saved = carts.save(fresh);
            log.info("Created guest cart {} for cookie {}", saved.getId(),
                    redact(cookieId));
            return saved;
        });
    }

    // ================================================================
    //  Mutation — these all save the cart and return the updated row
    // ================================================================

    /**
     * Add an item with already-resolved selections. Lines with the same
     * menu item AND the same selections merge quantities; otherwise
     * they're kept separate. Mirrors the original session
     * {@code Cart.add} logic exactly.
     */
    public Cart addLine(Cart cart, MenuItemView item, int qty,
                        List<Cart.SelectedOption> selections,
                        long unitPriceCentsWithUpcharges) {
        if (qty <= 0) return cart;
        List<Cart.SelectedOption> normalized = selections == null
                ? List.of()
                : List.copyOf(selections);

        if (cart.getLines() == null) {
            cart.setLines(new ArrayList<>());
        }

        for (Cart.CartLine existing : cart.getLines()) {
            if (existing.getMenuItemId().equals(item.getId())
                    && existing.getSelections().equals(normalized)) {
                existing.setQuantity(existing.getQuantity() + qty);
                return save(cart);
            }
        }
        Cart.CartLine line = new Cart.CartLine(
                UUID.randomUUID().toString(),
                item.getId(),
                item.getName(),
                unitPriceCentsWithUpcharges,
                qty,
                normalized
        );
        cart.getLines().add(line);
        return save(cart);
    }

    /**
     * Update quantity on an existing line. Zero or negative quantities
     * remove the line.
     */
    public Cart updateLineQuantity(Cart cart, String lineId, int qty) {
        if (cart.getLines() == null) return cart;
        if (qty <= 0) {
            return removeLine(cart, lineId);
        }
        for (Cart.CartLine line : cart.getLines()) {
            if (line.getLineId().equals(lineId)) {
                line.setQuantity(qty);
                return save(cart);
            }
        }
        // Line not found — no-op rather than throw; this can happen if
        // the customer has two tabs open and removed the line in one.
        return cart;
    }

    /** Remove a line by lineId. No-op if not present. */
    public Cart removeLine(Cart cart, String lineId) {
        if (cart.getLines() == null) return cart;
        cart.getLines().removeIf(l -> l.getLineId().equals(lineId));
        return save(cart);
    }

    /**
     * Persist changes to a cart. For guest carts, refreshes the TTL
     * window (rolling 30 days from last touch). User carts have no TTL.
     */
    public Cart save(Cart cart) {
        if (cart.isGuestCart()) {
            cart.setExpiresAt(Instant.now().plus(GUEST_TTL));
        } else {
            // Defensive: user carts never have an expiry
            cart.setExpiresAt(null);
        }
        return carts.save(cart);
    }

    /** Empty the line items but keep the cart row (post-checkout clear). */
    public Cart clear(Cart cart) {
        if (cart.getLines() != null) {
            cart.getLines().clear();
        }
        return save(cart);
    }

    public void clearActiveOrderId(String orderId) {
        if (orderId == null) return;
        carts.findByActiveOrderId(orderId).ifPresent(c -> {
            c.setActiveOrderId(null);
            carts.save(c);
            log.debug("Cleared activeOrderId {} from cart {}", orderId, c.getId());
        });
    }

    public void deleteByUser(String userId) {
        carts.findByUserId(userId).ifPresent(c -> {
            carts.delete(c);
            log.info("Deleted user cart for user {}", userId);
        });
    }

    public void deleteByCookie(String cookieId) {
        carts.findByCookieId(cookieId).ifPresent(c -> {
            carts.delete(c);
            log.info("Deleted guest cart for cookie {}", redact(cookieId));
        });
    }

    // ================================================================
//  Stale-line validation
// ================================================================

    /**
     * Strip cart lines that point at menu items no longer available
     * (deleted or marked unavailable), persist the cleaned cart, and
     * return a result describing what was removed.
     *
     * <p>Called from customer-facing cart-read paths (the /checkout page,
     * the nav-badge resolver). Mutations on /cart/add already validate
     * against the current menu, so this is only needed on lines cached
     * from earlier sessions.
     *
     * <p>If nothing was removed, returns the cart unchanged with an empty
     * removed-names list and does not touch the database.
     *
     * <p>Currently validates item-level availability only. Option-level
     * staleness (a selected choice that's no longer available, or a
     * required option group whose only available choice was removed) is
     * not yet handled — those lines stay in the cart and would surface as
     * a Stripe / order-creation error. See followup notes.
     */
    public CartValidation validateAndRepair(Cart cart) {
        if (cart == null || cart.getLines() == null || cart.getLines().isEmpty()) {
            return CartValidation.noChange(cart);
        }

        List<String> removed = new ArrayList<>();
        Iterator<Cart.CartLine> it = cart.getLines().iterator();
        while (it.hasNext()) {
            Cart.CartLine line = it.next();
            var item = menuService.findById(line.getMenuItemId()).orElse(null);
            if (item == null || !item.isAvailable()) {
                // Prefer the live menu name if we still have it, else fall
                // back to the snapshot name on the cart line. Either way the
                // customer sees something recognizable.
                String displayName = item != null ? item.getName() : line.getName();
                removed.add(displayName);
                it.remove();
                log.info("Removed stale cart line {} (item {}) from cart {} ({})",
                        line.getLineId(), line.getMenuItemId(), cart.getId(),
                        item == null ? "menu item deleted" : "marked unavailable");
            }
        }

        if (removed.isEmpty()) {
            return CartValidation.noChange(cart);
        }

        Cart saved = save(cart);
        return new CartValidation(saved, removed);
    }

    // ================================================================
    //  Merge on login
    // ================================================================

    /**
     * Merge a guest cart's contents into a user's cart, then delete
     * the guest cart row.
     *
     * <p>Merge policy: silent combine — line items with the same
     * {@code menuItemId} and identical {@code selections} sum quantities.
     * Different selections stay separate lines.
     *
     * <p>Called after successful login. Idempotent: if no guest cart
     * exists, no-op. If the user cart doesn't exist, creates it. If
     * the guest cart is empty, just deletes it.
     *
     * @return the user's cart after the merge (which is now the "live" cart)
     */
    public Cart mergeGuestIntoUser(String cookieId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required for merge");
        }

        Cart userCart = findOrCreateForUser(userId);

        if (cookieId == null || cookieId.isBlank()) {
            return userCart;
        }

        Optional<Cart> maybeGuest = carts.findByCookieId(cookieId);
        if (maybeGuest.isEmpty()) {
            return userCart;
        }
        Cart guestCart = maybeGuest.get();

        if (guestCart.isEmpty()) {
            carts.delete(guestCart);
            log.info("Discarded empty guest cart {} during merge into user {}",
                    guestCart.getId(), userId);
            return userCart;
        }

        int merged = 0;
        int appended = 0;
        for (Cart.CartLine guestLine : guestCart.getLines()) {
            Cart.CartLine matching = findMatchingLine(userCart.getLines(), guestLine);
            if (matching != null) {
                matching.setQuantity(matching.getQuantity() + guestLine.getQuantity());
                merged++;
            } else {
                userCart.getLines().add(copyLine(guestLine));
                appended++;
            }
        }

        carts.delete(guestCart);

        if (userCart.getActiveOrderId() == null && guestCart.getActiveOrderId() != null) {
            userCart.setActiveOrderId(guestCart.getActiveOrderId());
        }

        Cart savedUserCart = save(userCart);
        log.info("Merged guest cart {} into user cart {} for user {} " +
                        "(merged {} lines, appended {} lines)",
                guestCart.getId(), savedUserCart.getId(), userId, merged, appended);
        return savedUserCart;
    }

    /**
     * Find a line in {@code lines} that matches the candidate's menu item
     * AND selections (same product, same option choices). Returns null
     * if none.
     */
    private static Cart.CartLine findMatchingLine(List<Cart.CartLine> lines,
                                                  Cart.CartLine candidate) {
        if (lines == null) return null;
        for (Cart.CartLine existing : lines) {
            if (existing.getMenuItemId().equals(candidate.getMenuItemId())
                    && existing.getSelections().equals(candidate.getSelections())) {
                return existing;
            }
        }
        return null;
    }

    /**
     * Defensive copy when moving a line between carts so we don't share
     * mutable references across two persisted documents.
     */
    private static Cart.CartLine copyLine(Cart.CartLine source) {
        return new Cart.CartLine(
                source.getLineId(),
                source.getMenuItemId(),
                source.getName(),
                source.getUnitPriceCents(),
                source.getQuantity(),
                new ArrayList<>(source.getSelections())
        );
    }

    private static String redact(String cookieId) {
        if (cookieId == null || cookieId.length() < 8) return "***";
        return cookieId.substring(0, 4) + "..." + cookieId.substring(cookieId.length() - 4);
    }
}