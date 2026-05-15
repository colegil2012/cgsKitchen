package com.celtech.solutions.cgsKitchen.controllers;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.Cart;
import com.celtech.solutions.cgsKitchen.services.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Public Thymeleaf storefront pages.
 *
 * <p>The {@code @ModelAttribute} below makes brand info available to
 * every template without each handler having to populate it.
 */
@Controller
@RequiredArgsConstructor
public class StorefrontController {

    private final MenuService menuService;
    private final AppProperties props;
    private final Cart cart;

    /** Made available in every model rendered by this controller. */
    @ModelAttribute("brand")
    public AppProperties.Storefront brand() {
        return props.storefront();
    }

    @ModelAttribute("cart")
    public Cart cart() {
        return cart;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("featured", menuService.findAvailable().stream()
                .filter(m -> m.getBadge() != null)
                .limit(3)
                .toList());
        return "home";
    }

    @GetMapping("/menu")
    public String menu(Model model) {
        model.addAttribute("menuByCategory", menuService.findGroupedByCategory());
        return "menu";
    }

    @GetMapping("/about")
    public String about() {
        return "about";
    }

    @GetMapping("/contact")
    public String contact() {
        return "contact";
    }
}
