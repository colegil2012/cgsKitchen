package com.celtech.solutions.cgsKitchen.controllers.storefront;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.config.CartCookieFilter;
import com.celtech.solutions.cgsKitchen.delivery.DeliveryProvider;
import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.models.user.Address;
import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.shop.CartService;
import com.celtech.solutions.cgsKitchen.services.storefront.shop.CheckoutService;
import com.celtech.solutions.cgsKitchen.services.storefront.menu.MenuService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.user.AddressService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import com.stripe.exception.StripeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * Cart manipulation and checkout flow (lazy-mount Elements).
 *
 * <p><b>Cart persistence:</b> the cart is a Mongo document
 * ({@link Cart}) resolved per-request via {@link CartService}. For each
 * incoming request:
 * <ul>
 *   <li>If the visitor is signed in → user cart (keyed by userId)</li>
 *   <li>Otherwise → guest cart keyed by the {@code cgsk_cart} cookie,
 *       which {@link CartCookieFilter} ensures is present</li>
 * </ul>
 *
 * <p>{@link #currentCart} is the single resolution helper used by all
 * endpoints. OrderService takes cart line items + subtotal directly,
 * so the controller passes {@code cart.getLines()} and
 * {@code cart.getSubtotalCents()} rather than the cart object itself.
 *
 * <p><b>In-flight order tracking:</b> the id of the active
 * {@code PENDING_PAYMENT} Order is stored on the {@code Cart} document
 * itself ({@code Cart.activeOrderId}). This survives HTTP session
 * rotation, login transitions, and multi-device access, and means
 * exactly one in-flight order per cart at a time.
 *
 * <p><b>Flow:</b>
 * <ol>
 *   <li>{@code GET /checkout} — ensures a PENDING_PAYMENT Order exists
 *       for the visitor's cart. No Stripe call is made here.</li>
 *   <li>{@code POST /checkout/init-payment} — the page's JS calls this
 *       after mount; it lazily creates (or fetches) the Stripe
 *       PaymentIntent and returns the client_secret. Bots and
 *       prefetchers never reach this endpoint.</li>
 *   <li>{@code POST /checkout/quote-delivery} — when the user requests
 *       a delivery quote, the server-computed fee is persisted onto the
 *       Order with a {@code quoteId}; the response carries the id.</li>
 *   <li>{@code POST /checkout/update-intent} — called by JS when
 *       fulfillment, delivery quote, or save-card toggle changes.
 *       Refreshes the Order from the cart, re-derives the delivery fee
 *       from the persisted quote (the client only sends a quoteId), and
 *       pushes the new amount to Stripe. The same client_secret stays
 *       valid.</li>
 *   <li>Stripe.js confirms payment client-side; the
 *       {@code payment_intent.succeeded} webhook transitions the Order
 *       to PAID and clears {@code cart.activeOrderId}.</li>
 * </ol>
 */
@Slf4j
@Controller
@Validated
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final UserService userService;
    private final AddressService addressService;
    private final MenuService menuService;
    private final OrderService orderService;
    private final CheckoutService checkoutService;
    private final EventService eventService;
    private final DeliveryProvider delivery;
    private final AppProperties props;

    // ================================================================
    //  Cart manipulation
    // ================================================================

    @PostMapping("/cart/add")
    public String addToCart(
            @RequestParam @NotBlank String menuItemId,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam Map<String, String> allParams,
            HttpServletRequest request,
            Authentication auth,
            RedirectAttributes redirect) {
        var item = menuService.findById(menuItemId).orElse(null);
        if (item == null || !item.isAvailable()) {
            redirect.addFlashAttribute("error", "That item isn't available right now.");
            return "redirect:/menu";
        }

        Map<String, List<String>> submitted = new HashMap<>();
        allParams.forEach((k, v) -> {
            if (k.startsWith("opt_") && v != null && !v.isBlank()) {
                String groupId = k.substring(4).replace("[]", "");
                submitted.computeIfAbsent(groupId, g -> new ArrayList<>())
                        .addAll(Arrays.asList(v.split(",")));
            }
        });

        try {
            var resolved = menuService.resolveSelections(item, submitted);
            Cart cart = currentCart(request, auth);
            cartService.addLine(cart, item, quantity, resolved.selections(),
                    resolved.unitPriceCents());
            redirect.addFlashAttribute("addedItem", item.getName());
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/menu#" + item.getCategoryId();
        }
        return "redirect:/menu#" + item.getCategoryId();
    }

    @PostMapping("/cart/update")
    public String updateCart(@RequestParam String lineId,
                             @RequestParam int quantity,
                             HttpServletRequest request,
                             Authentication auth) {
        Cart cart = currentCart(request, auth);
        cartService.updateLineQuantity(cart, lineId, quantity);
        return "redirect:/checkout";
    }

    @PostMapping("/cart/remove")
    public String removeFromCart(@RequestParam String lineId,
                                 HttpServletRequest request,
                                 Authentication auth) {
        Cart cart = currentCart(request, auth);
        cartService.removeLine(cart, lineId);
        return "redirect:/checkout";
    }

    // ================================================================
    //  Checkout page
    // ================================================================

    @GetMapping("/checkout")
    public String checkout(Model model,
                           Authentication auth,
                           HttpServletRequest request,
                           @RequestParam(required = false) Boolean cancelled) {
        Cart cart = currentCart(request, auth);

        // Sweep stale lines (items deleted or marked unavailable since the
        // cart was last touched). Best-effort: surface a notice if anything
        // was removed; the customer sees the cleaned cart immediately.
        var validation = cartService.validateAndRepair(cart);
        cart = validation.cart();
        if (validation.hadRemovals()) {
            model.addAttribute("notice",
                    "Removed unavailable item"
                            + (validation.removedItemNames().size() == 1 ? "" : "s")
                            + ": " + String.join(", ", validation.removedItemNames()));
        }

        if (cart.isEmpty()) {
            return "redirect:/menu";
        }

        // ----- Event gate ----------------------------------------
        // No active event = we're closed. Show a friendly "cart saved,
        // see you next time" page rather than going through the full
        // checkout flow. Cart persists in DB.
        if (eventService.findCurrentlyOpen().isEmpty()) {
            java.time.ZoneId zone = zone();
            eventService.findNextScheduled(zone).ifPresent(next -> {
                model.addAttribute("nextEventForClosed", next);
                model.addAttribute("zoneId", zone);
            });
            return "storefront/checkout-closed";
        }

        if (cancelled != null && cancelled) {
            model.addAttribute("notice",
                    "Checkout cancelled. Your cart is still here when you're ready.");
        }

        // Resolve current user, autofill form, gather saved addresses.
        CheckoutForm form = new CheckoutForm();
        boolean signedIn = false;
        List<Address> savedAddresses = List.of();
        String userId = null;
        String userEmail = null;
        User user = null;

        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            userEmail = auth.getName();
            user = userService.findByEmail(userEmail).orElse(null);
            if (user != null) {
                signedIn = true;
                userId = user.getId();
                form.setName(user.getDisplayName());
                form.setEmail(user.getEmail());
                form.setPhone(user.getPhone());
                savedAddresses = addressService.findByUser(user.getId());
                addressService.findPrimary(user.getId())
                        .ifPresent(a -> form.setSelectedAddressId(a.getId()));
            }
        }

        // Ensure we have a PENDING_PAYMENT Order for this session.
        Order order = resolveActiveOrder(cart, userId, form);

        // Pickup ETA from the order's promised-ready stamp. Rendered server-side
        // because the /api/public/** chain runs anonymous and can't see the
        // signed-in user's cart (it'd resolve an empty guest cart → 0 min).
        if (order.getPromisedReadyAt() != null) {
            long mins = Math.max(0,
                    java.time.Duration.between(java.time.Instant.now(),
                            order.getPromisedReadyAt()).toMinutes());
            model.addAttribute("pickupEtaMinutes", Math.max(mins, 5));
        }

        model.addAttribute("checkoutForm", form);
        model.addAttribute("signedIn", signedIn);
        model.addAttribute("savedAddresses", savedAddresses);
        model.addAttribute("stripePublishableKey", props.stripe().publishableKey());
        model.addAttribute("orderId", order.getId());
        model.addAttribute("orderTotalCents", order.getTotalCents());
        model.addAttribute("taxRate", props.storefront().taxRate());
        model.addAttribute("checkoutDebugJs", props.checkout() != null && props.checkout().debugJs());
        return "storefront/checkout";
    }

    /**
     * Find-or-create the active Order for this visitor's cart.
     * If the cart points at one and it's still PENDING_PAYMENT, reuse
     * (refreshing it from the current cart). Otherwise create new and
     * store its id on the cart so subsequent visits — across sessions,
     * devices, and login state — find the same in-flight order.
     */
    private Order resolveActiveOrder(Cart cart, String userId, CheckoutForm form) {
        if (cart.getActiveOrderId() != null) {
            Order existing = orderService.findById(cart.getActiveOrderId()).orElse(null);
            if (existing != null && existing.getStatus() == Order.Status.PENDING_PAYMENT) {
                // Refresh totals + line items in case the cart changed.
                return orderService.refreshFromCart(
                        existing,
                        cart.getLines(), cart.getSubtotalCents(),
                        existing.getFulfillment(),
                        existing.getCustomerName(), existing.getCustomerEmail(),
                        existing.getCustomerPhone(), existing.getDeliveryAddress(),
                        existing.getDeliveryFeeCents());
            }
            // Order is gone or no longer PENDING_PAYMENT — clear the
            // stale pointer and fall through to create a fresh one.
            cart.setActiveOrderId(null);
            cartService.save(cart);
        }

        // Resolve the live event to stamp on the order. The checkout-closed
        // gate in checkout() should guarantee one exists by the time we get
        // here; orElseThrow is a belt-and-suspenders assertion that surfaces
        // the real problem instead of a Mongo validation 500 if some path
        // ever reaches order creation outside a live event.
        String eventId = eventService.findCurrentlyOpen()
                .map(com.celtech.solutions.cgsKitchen.models.storefront.event.Event::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Checkout reached resolveActiveOrder with no live event"));

        Order created = orderService.createFromCart(
                cart.getLines(), cart.getSubtotalCents(),
                Order.Fulfillment.PICKUP,
                userId,
                form.getName(), form.getEmail(), form.getPhone(),
                null,
                0L,
                eventId);
        cart.setActiveOrderId(created.getId());
        cartService.save(cart);
        log.info("Created new checkout Order {} for cart {}", created.getId(), cart.getId());
        return created;
    }

    @PostMapping("/checkout/quote-delivery")
    @ResponseBody
    public DeliveryQuoteResponse quoteDelivery(@RequestBody DeliveryQuoteRequest req,
                                               HttpServletRequest request,
                                               Authentication auth) {
        Cart cart = currentCart(request, auth);
        var quote = delivery.quote(new DeliveryProvider.QuoteRequest(
                props.delivery().pickupAddress(),
                req.address(),
                cart.getSubtotalCents()
        ));

        // Persist the quote on the active order so /checkout/update-intent
        // can re-derive the fee server-side. Avoids trusting the client to
        // echo back a deliveryFeeCents value (which would let a malicious
        // user reduce the total).
        String quoteId = null;
        if (cart.getActiveOrderId() != null) {
            Order order = orderService.findById(cart.getActiveOrderId()).orElse(null);
            if (order != null && order.getStatus() == Order.Status.PENDING_PAYMENT) {
                quoteId = java.util.UUID.randomUUID().toString();
                order.setCurrentDeliveryQuote(Order.DeliveryQuote.builder()
                        .quoteId(quoteId)
                        .address(req.address())
                        .feeCents(quote.feeCents())
                        .etaMinutes(quote.etaMinutes())
                        .provider(delivery.name())
                        .issuedAt(java.time.Instant.now())
                        .expiresAt(java.time.Instant.now().plusSeconds(600))
                        .build());
                orderService.save(order);
            }
        }

        return new DeliveryQuoteResponse(
                quoteId,
                quote.feeCents(),
                quote.getFeeDisplay(),
                quote.etaMinutes() + " min",
                delivery.name()
        );
    }

    // ================================================================
    //  Lazy PaymentIntent — called by JS after the page is interactive
    // ================================================================

    /**
     * Create (or fetch) the Stripe PaymentIntent for the active order.
     * Called by the checkout page's JS once the user is actually present
     * and the Payment Element is about to mount.
     *
     * <p>This is the lazy counterpart to the eager creation that used to
     * happen inside {@code GET /checkout}. Moving the Stripe call here
     * means:
     * <ul>
     *   <li>Bots / link previewers / prefetchers can't trigger PI creation
     *       (they don't run our fetch()).</li>
     *   <li>Users who open /checkout and bounce never burn a Stripe API
     *       call.</li>
     *   <li>The PI's initial amount can include any client-side state
     *       (delivery quote already in hand, save-card toggle, etc.) so
     *       the very first amount we send to Stripe is correct.</li>
     * </ul>
     *
     * <p>Idempotent: subsequent calls for the same order return the same
     * client_secret. The {@code pi_create_<orderId>} idempotency key on
     * the Stripe side guarantees this even under double-submit.
     */
    @PostMapping("/checkout/init-payment")
    @ResponseBody
    public InitPaymentResponse initPayment(Authentication auth, HttpServletRequest request) {
        Cart cart = currentCart(request, auth);
        if (cart.getActiveOrderId() == null) {
            throw new IllegalStateException("No active order for this cart");
        }
        Order order = orderService.findById(cart.getActiveOrderId())
                .orElseThrow(() -> new IllegalStateException("Active order not found"));
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            throw new IllegalStateException("Order is not pending payment");
        }

        String userEmail = signedInEmail(auth);
        try {
            var pi = checkoutService.ensurePaymentIntent(order, userEmail, false);
            return new InitPaymentResponse(
                    pi.clientSecret(),
                    pi.paymentIntentId(),
                    order.getId(),
                    order.getTotalCents());
        } catch (StripeException ex) {
            log.error("init-payment failed for order {}", order.getId(), ex);
            throw new RuntimeException("Payment system temporarily unavailable");
        }
    }

    public record InitPaymentResponse(
            String clientSecret,
            String paymentIntentId,
            String orderId,
            long orderTotalCents
    ) {}

    // ================================================================
    //  Live update endpoint — called by JS when totals change
    // ================================================================

    /**
     * Update the active PENDING_PAYMENT Order with new fulfillment/address/
     * fee/save-card state, push the new amount to Stripe. Returns the
     * recomputed totals so the page can refresh the summary.
     */
    @PostMapping("/checkout/update-intent")
    @ResponseBody
    public UpdateIntentResponse updateIntent(
            @Valid @RequestBody UpdateIntentRequest req,
            HttpServletRequest request,
            Authentication auth) {
        Cart cart = currentCart(request, auth);
        if (cart.getActiveOrderId() == null) {
            throw new IllegalStateException("No active checkout session");
        }
        Order order = orderService.findById(cart.getActiveOrderId())
                .orElseThrow(() -> new IllegalStateException("Active order not found"));

        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            throw new IllegalStateException("Order is not pending payment");
        }

        Order.Fulfillment fulfillment = "delivery".equalsIgnoreCase(req.fulfillment())
                ? Order.Fulfillment.DELIVERY
                : Order.Fulfillment.PICKUP;

        // Re-derive the delivery fee server-side from the persisted quote.
        // The client only tells us *which* quote to use; the cents value
        // comes from the Order doc we stamped during /checkout/quote-delivery.
        long deliveryFee = 0L;
        if (fulfillment == Order.Fulfillment.DELIVERY
                && req.deliveryQuoteId() != null
                && order.getCurrentDeliveryQuote() != null
                && req.deliveryQuoteId().equals(order.getCurrentDeliveryQuote().getQuoteId())) {
            var q = order.getCurrentDeliveryQuote();
            if (q.getExpiresAt() != null
                    && q.getExpiresAt().isAfter(java.time.Instant.now())) {
                deliveryFee = q.getFeeCents();
            } else {
                log.warn("Stale delivery quote {} for order {} — forcing re-quote",
                        q.getQuoteId(), order.getId());
                throw new IllegalStateException(
                        "Delivery quote expired. Please re-quote.");
            }
        }
        // Resolve the delivery address from either saved id or typed fields.
        String userEmail = signedInEmail(auth);
        User user = userEmail == null ? null
                : userService.findByEmail(userEmail).orElse(null);

        String deliveryAddressLine = null;
        if (fulfillment == Order.Fulfillment.DELIVERY) {
            if (req.selectedAddressId() != null && user != null) {
                Address selected = addressService.findByIdForUser(req.selectedAddressId(), user.getId())
                        .orElse(null);
                if (selected != null) {
                    deliveryAddressLine = selected.toSingleLine();
                }
            } else if (req.address() != null && req.address().line1() != null) {
                deliveryAddressLine = req.address().toSingleLine();
                if (user != null && req.saveAddress()) {
                    Address toSave = Address.builder()
                            .label(req.address().label())
                            .line1(req.address().line1())
                            .line2(req.address().line2())
                            .city(req.address().city())
                            .state(req.address().state())
                            .postalCode(req.address().postalCode())
                            .country(req.address().country() == null ? "US" : req.address().country())
                            .build();
                    var result = addressService.createWithResult(user.getId(), toSave, false);
                    Address saved = result.address();
                    if (result.isExistingDuplicate()) {
                        log.info("Reused existing address {} for user {} (duplicate detected)",
                                saved.getId(), user.getId());
                    }
                }
            }
        }

        // Refresh the order, then push the new total to Stripe.
        Order refreshed = orderService.refreshFromCart(
                order,
                cart.getLines(), cart.getSubtotalCents(),
                fulfillment,
                req.name(), req.email(), req.phone(),
                deliveryAddressLine,
                deliveryFee);

        try {
            checkoutService.updatePaymentIntent(refreshed, req.saveCard());
        } catch (StripeException e) {
            log.error("Failed to update PaymentIntent for order {}", refreshed.getId(), e);
            throw new RuntimeException("Could not update payment: " + e.getMessage());
        }

        // Sync the user's profile if they edited name/phone in the form.
        if (user != null) {
            try {
                boolean changed = false;
                if (req.name() != null && !req.name().equals(user.getDisplayName())) changed = true;
                if (req.phone() != null && !req.phone().equals(user.getPhone())) changed = true;
                if (changed) {
                    userService.updateProfile(user.getId(), req.name(), req.phone());
                }
            } catch (Exception e) {
                log.warn("Profile sync failed for user {}", user.getId(), e);
            }
        }

        return new UpdateIntentResponse(
                refreshed.getSubtotalCents(),
                refreshed.getTaxCents(),
                refreshed.getDeliveryFeeCents(),
                refreshed.getTotalCents()
        );
    }

    // ================================================================
    //  Order confirm — clears the visitor's cart
    // ================================================================

    @GetMapping("/order/confirm")
    public String orderConfirm(@RequestParam(required = false) String session_id,
                               @RequestParam(required = false, name = "payment_intent") String paymentIntentId,
                               @RequestParam(required = false) Boolean mock,
                               HttpServletRequest request,
                               Authentication auth,
                               Model model) {
        Order order = null;
        if (paymentIntentId != null) {
            order = orderService.findByPaymentIntentId(paymentIntentId).orElse(null);
        }
        if (order != null) {
            model.addAttribute("order", order);
        }

        // Clear the cart row but keep it alive (rolling TTL refresh).
        // Customer can place a fresh order without re-getting a cookie.
        Cart cart = currentCart(request, auth);
        cartService.clear(cart);
        cart.setActiveOrderId(null);
        cartService.save(cart);

        if (mock != null && mock) {
            model.addAttribute("mock", true);
        }
        return "storefront/order-confirm";
    }

    // ================================================================
    //  Helpers
    // ================================================================

    /**
     * Resolve the current cart for this request. Single rule:
     * authenticated → user cart; otherwise → guest cart by cookie.
     *
     * <p>The cookie is guaranteed present by {@link CartCookieFilter}.
     * If we somehow get here without it (e.g. a path that the filter
     * skipped but a controller still maps), we throw — that would be a
     * configuration mismatch worth surfacing loudly.
     */
    private Cart currentCart(HttpServletRequest request, Authentication auth) {
        String userId = resolveUserId(auth);
        if (userId != null) {
            return cartService.findOrCreateForUser(userId);
        }
        Object cookieAttr = request.getAttribute(CartCookieFilter.REQUEST_ATTR);
        if (cookieAttr == null) {
            throw new IllegalStateException(
                    "Cart cookie attribute missing — CartCookieFilter not running? path=" +
                            request.getRequestURI());
        }
        return cartService.findOrCreateForGuest(cookieAttr.toString());
    }

    private String resolveUserId(Authentication auth) {
        String email = signedInEmail(auth);
        if (email == null) return null;
        return userService.findByEmail(email).map(User::getId).orElse(null);
    }

    private String signedInEmail(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }

    /** Storefront-configured zone, with safe fallback. */
    private java.time.ZoneId zone() {
        String tz = props.storefront().timezone();
        return java.time.ZoneId.of(tz == null || tz.isBlank() ? "America/New_York" : tz);
    }

    // ================================================================
    //  Form / DTOs
    // ================================================================

    @Data
    public static class CheckoutForm {
        @NotBlank private String name;
        @NotBlank private String email;
        private String phone;
        private String fulfillment;
        private String selectedAddressId;
    }

    public record UpdateIntentRequest(
            String name,
            String email,
            String phone,
            String fulfillment,
            String selectedAddressId,
            AddressInput address,
            boolean saveAddress,
            boolean saveCard,
            String deliveryQuoteId
    ) {}

    public record AddressInput(
            String label,
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String country,
            String notes
    ) {
        public String toSingleLine() {
            StringBuilder sb = new StringBuilder();
            if (line1 != null) sb.append(line1);
            if (line2 != null && !line2.isBlank()) sb.append(", ").append(line2);
            if (city != null) sb.append(", ").append(city);
            if (state != null) sb.append(", ").append(state);
            if (postalCode != null) sb.append(' ').append(postalCode);
            return sb.toString();
        }
    }

    public record UpdateIntentResponse(
            long subtotalCents,
            long taxCents,
            long deliveryFeeCents,
            long totalCents
    ) {}

    public record DeliveryQuoteResponse(
            String quoteId,
            long feeCents,
            String feeDisplay,
            String eta,
            String provider
    ) {}

    public record DeliveryQuoteRequest(String address) {}

}