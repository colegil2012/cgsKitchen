package com.celtech.solutions.cgsKitchen.controllers;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.delivery.DeliveryProvider;
import com.celtech.solutions.cgsKitchen.models.Cart;
import com.celtech.solutions.cgsKitchen.models.Order;
import com.celtech.solutions.cgsKitchen.services.CheckoutService;
import com.celtech.solutions.cgsKitchen.services.MenuService;
import com.celtech.solutions.cgsKitchen.services.OrderService;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Cart and checkout flow.
 *
 * <p>Cart manipulation uses POST + redirect-after-POST so refresh
 * doesn't double-submit. The cart is session-scoped so it follows the
 * visitor across pages.
 */
@Slf4j
@Controller
@Validated
@RequiredArgsConstructor
public class CartController {

    private final Cart cart;
    private final MenuService menuService;
    private final OrderService orderService;
    private final CheckoutService checkoutService;
    private final DeliveryProvider delivery;
    private final AppProperties props;

    @ModelAttribute("brand")
    public AppProperties.Storefront brand() {
        return props.storefront();
    }

    @ModelAttribute("cart")
    public Cart cart() {
        return cart;
    }

    @PostMapping("/cart/add")
    public String addToCart(
            @RequestParam @NotBlank String menuItemId,
            @RequestParam(defaultValue = "1") int quantity,
            RedirectAttributes redirect
    ) {
        var item = menuService.findById(menuItemId).orElse(null);
        if (item == null || !item.isAvailable()) {
            redirect.addFlashAttribute("error", "That item isn't available right now.");
            return "redirect:/menu";
        }
        cart.add(item, quantity);
        redirect.addFlashAttribute("addedItem", item.getName());
        return "redirect:/menu#" + item.getCategory();
    }

    @PostMapping("/cart/update")
    public String updateCart(
            @RequestParam String menuItemId,
            @RequestParam int quantity
    ) {
        cart.updateQuantity(menuItemId, quantity);
        return "redirect:/checkout";
    }

    @PostMapping("/cart/remove")
    public String removeFromCart(@RequestParam String menuItemId) {
        cart.remove(menuItemId);
        return "redirect:/checkout";
    }

    @GetMapping("/checkout")
    public String checkout(Model model,
                          @RequestParam(required = false) Boolean cancelled) {
        if (cart.isEmpty()) {
            return "redirect:/menu";
        }
        if (cancelled != null && cancelled) {
            model.addAttribute("notice",
                "Checkout cancelled. Your cart is still here when you're ready.");
        }
        if (!model.containsAttribute("checkoutForm")) {
            model.addAttribute("checkoutForm", new CheckoutForm());
        }
        return "checkout";
    }

    @PostMapping("/checkout/quote-delivery")
    @ResponseBody
    public DeliveryQuoteResponse quoteDelivery(@RequestBody DeliveryQuoteRequest req) {
        var quote = delivery.quote(new DeliveryProvider.QuoteRequest(
                props.delivery().pickupAddress(),
                req.address(),
                cart.getSubtotalCents()
        ));
        return new DeliveryQuoteResponse(
                quote.feeCents(),
                quote.getFeeDisplay(),
                quote.etaMinutes() + " min",
                delivery.name()
        );
    }

    @PostMapping("/checkout")
    public String submitCheckout(
            @Valid @ModelAttribute("checkoutForm") CheckoutForm form,
            RedirectAttributes redirect
    ) {
        if (cart.isEmpty()) {
            return "redirect:/menu";
        }

        var fulfillment = "delivery".equalsIgnoreCase(form.getFulfillment())
                ? Order.Fulfillment.DELIVERY
                : Order.Fulfillment.PICKUP;

        long deliveryFeeCents = 0;
        if (fulfillment == Order.Fulfillment.DELIVERY && form.getDeliveryFeeCents() != null) {
            deliveryFeeCents = form.getDeliveryFeeCents();
        }

        var order = orderService.createFromCart(
                cart, fulfillment,
                form.getName(), form.getEmail(), form.getPhone(),
                fulfillment == Order.Fulfillment.DELIVERY ? form.getAddress() : null,
                deliveryFeeCents
        );

        try {
            String redirectUrl = checkoutService.createCheckoutSession(order, baseUrl());
            // Stripe success_url will redirect to /order/confirm, where we'll clear the cart.
            return "redirect:" + redirectUrl;
        } catch (StripeException e) {
            log.error("Checkout session creation failed for order {}", order.getId(), e);
            redirect.addFlashAttribute("error",
                    "We couldn't reach the payment processor. Please try again.");
            return "redirect:/checkout";
        }
    }

    @GetMapping("/order/confirm")
    public String orderConfirm(@RequestParam(required = false) String session_id,
                              @RequestParam(required = false) Boolean mock,
                              Model model) {
        if (session_id != null) {
            orderService.findByCheckoutSessionId(session_id)
                    .ifPresent(order -> model.addAttribute("order", order));
        }
        cart.clear();
        if (mock != null && mock) {
            model.addAttribute("mock", true);
        }
        return "order-confirm";
    }

    private String baseUrl() {
        // For local dev — production uses request-derived host or config.
        // Inject HttpServletRequest in a real deployment for accuracy.
        return "http://localhost:8080";
    }

    // ---- Form / DTOs ----

    @lombok.Data
    public static class CheckoutForm {
        @NotBlank private String name;
        @NotBlank private String email;
        private String phone;
        private String fulfillment;       // "pickup" or "delivery"
        private String address;
        private Long deliveryFeeCents;    // populated by JS after delivery quote
    }

    public record DeliveryQuoteRequest(String address) {}
    public record DeliveryQuoteResponse(long feeCents, String feeDisplay,
                                        String eta, String provider) {}
}
