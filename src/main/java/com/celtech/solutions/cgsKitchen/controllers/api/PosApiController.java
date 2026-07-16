package com.celtech.solutions.cgsKitchen.controllers.api;

import com.celtech.solutions.cgsKitchen.config.properties.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.terminal.ConnectionToken;
import com.stripe.model.terminal.Reader;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.terminal.ConnectionTokenCreateParams;
import com.stripe.param.terminal.ReaderCancelActionParams;
import com.stripe.param.terminal.ReaderProcessPaymentIntentParams;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the POS terminal (and any other authenticated
 * external client). Auth is via the X-API-Key / Authorization: Bearer
 * header configured in {@code app.api-key}.
 *
 * <p>Three groups of endpoints:
 * <ul>
 *   <li>{@code /api/orders} — list, get, status updates
 *   <li>{@code /api/pos/orders} — create an order from POS-entered items
 *   <li>{@code /api/terminal/*} — Stripe Terminal connection-token and
 *       payment-intent issuance
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PosApiController {

    private final UserService userService;

    // ------------------------------------------------------------------
    //  POS customer lookup
    //  Returns 200 {userId, displayName} when the email matches a registered user;
    //  404 when there's no match. Intentionally minimal
    // ------------------------------------------------------------------

    @GetMapping("/pos/customers/lookup")
    public ResponseEntity<?> lookupCustomer(@RequestParam String email) {
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("bad_request", "email is required"));
        }
        return userService.findByEmail(email.trim())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(
                        new CustomerMatch(u.getId(), u.getEmail(), u.getDisplayName())))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ErrorResponse("not_found",
                                "No registered customer with that email.")));
    }

    public record CustomerMatch(String userId, String email, String displayName) {}

    public record ErrorResponse(String code, String message) {}
}