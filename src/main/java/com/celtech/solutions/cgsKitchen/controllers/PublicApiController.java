package com.celtech.solutions.cgsKitchen.controllers;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.delivery.DeliveryProvider;
import com.celtech.solutions.cgsKitchen.models.MenuItem;
import com.celtech.solutions.cgsKitchen.services.MenuService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public REST endpoints — no auth required.
 *
 * <p>Used by the POS terminal for menu sync, and by the Thymeleaf
 * storefront's JS for live delivery quote.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicApiController {

    private final MenuService menuService;
    private final DeliveryProvider delivery;
    private final AppProperties props;

    @GetMapping("/menu")
    public List<MenuItem> menu() {
        return menuService.findAvailable();
    }

    @PostMapping("/delivery/quote")
    public DeliveryQuoteResponse quote(@RequestBody QuoteRequest req) {
        var q = delivery.quote(new DeliveryProvider.QuoteRequest(
                props.delivery().pickupAddress(),
                req.address(),
                req.cartTotalCents() == null ? 0 : req.cartTotalCents()
        ));
        return new DeliveryQuoteResponse(
                q.feeCents(),
                q.getFeeDisplay(),
                q.etaMinutes() + " min",
                delivery.name()
        );
    }

    public record QuoteRequest(@NotBlank String address, Long cartTotalCents) {}
    public record DeliveryQuoteResponse(long feeCents, String feeDisplay,
                                        String eta, String provider) {}
}
