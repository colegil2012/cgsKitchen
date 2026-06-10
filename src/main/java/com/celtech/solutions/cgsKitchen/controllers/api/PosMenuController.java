package com.celtech.solutions.cgsKitchen.controllers.api;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionChoice;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.view.MenuItemView;
import com.celtech.solutions.cgsKitchen.services.storefront.menu.MenuService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;
import java.util.List;

/**
 * POS-facing inventory API — view the full menu (including 86'd items) and
 * toggle availability of items and option choices. Lives on the API-key
 * authenticated chain ({@code /api/**}); the Thymeleaf {@code AdminMenuController}
 * is on the session chain the POS can't reach, so this exposes the subset
 * the POS needs over the key-auth surface.
 *
 * <p><b>Scope:</b> availability only (86 / un-86). Editing item fields
 * (name, price, etc.) remains in the admin portal. All mutations delegate
 * to {@link MenuService} so the logic isn't duplicated.
 *
 * <p>Availability is set explicitly ({@code {"available": false}}) rather
 * than flipped, so a double request from the POS can't bounce the state.
 */
@Slf4j
@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
public class PosMenuController {

    private final MenuService menuService;

    // ---------------- Read ----------------

    @GetMapping("/all")
    public List<MenuItemView> all() {
        return menuService.findAll();
    }

    @GetMapping("/menu")
    public List<MenuItemView> menu() {
        return menuService.findAvailable();
    }


    // ---------------- Mutations ----------------

    @PostMapping("/items/{id}/availability")
    public ResponseEntity<?> setItemAvailability(
            @PathVariable String id,
            @RequestBody AvailabilityRequest req) {
        try {
            MenuItemView updated = menuService.setItemAvailable(id, req.available());
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Menu item " + id + " not found"));
        }
    }

    @PostMapping("/choices/{id}/availability")
    public ResponseEntity<?> setChoiceAvailability(
            @PathVariable String id,
            @RequestBody ChoiceAvailabilityRequest req) {
        try {
            OptionChoice updated =
                    menuService.setChoiceAvailable(id, req.available(), req.reason());
            return ResponseEntity.ok(new ChoiceView(
                    updated.getId(),
                    updated.getLabel(),
                    updated.isAvailable(),
                    updated.getUnavailableReason()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Option choice " + id + " not found"));
        }
    }

    // ---------------- DTOs ----------------

    public record AvailabilityRequest(@NotNull Boolean available) {}

    public record ChoiceAvailabilityRequest(@NotNull Boolean available, String reason) {}

    public record ChoiceView(String id, String label, boolean available,
                             String unavailableReason) {}

    public record ErrorResponse(String code, String message) {}
}