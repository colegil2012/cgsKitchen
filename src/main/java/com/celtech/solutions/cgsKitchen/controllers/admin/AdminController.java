package com.celtech.solutions.cgsKitchen.controllers.admin;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen.OrderRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.MenuItemRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.OptionChoiceRepository;
import com.celtech.solutions.cgsKitchen.repositories.user.UserRepository;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.KitchenQuoteService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Admin dashboard — at-a-glance counters + entry points to the
 * sub-areas (menu, options, orders, users). All routes under {@code /admin}
 * are gated by {@code ROLE_ADMIN} via SecurityConfig.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final OrderRepository orders;
    private final OrderService orderService;
    private final UserRepository users;
    private final MenuItemRepository menuItems;
    private final OptionChoiceRepository optionChoices;
    private final KitchenQuoteService kitchenQuotes;

    @GetMapping
    public String dashboard(Model model) {
        long totalOrders   = orders.count();
        long totalUsers    = users.count();
        long abandonedCheckouts = orders.findByStatus(Order.Status.PENDING_PAYMENT).size();
        long inKitchen     = orders.findByStatus(Order.Status.IN_KITCHEN).size();
        long ready         = orders.findByStatus(Order.Status.READY).size();
        long unavailableChoices = optionChoices.findByAvailableFalse().size();
        long unavailableItems   = menuItems.findByAvailableFalse().size();
        long currentWait = kitchenQuotes.currentWait().waitMinutes();

        // Recent orders panel: paid-and-beyond only. PENDING_PAYMENT churn
        // from cart-page renders would otherwise dominate this list.
        var recent = orderService.findAdminExcludingPending(
                PageRequest.of(0, 10));

        model.addAttribute("totalOrders", totalOrders);
        model.addAttribute("totalUsers", totalUsers);
        model.addAttribute("abandonedCheckouts", abandonedCheckouts);
        model.addAttribute("inKitchenCount", inKitchen);
        model.addAttribute("readyCount", ready);
        model.addAttribute("unavailableChoices", unavailableChoices);
        model.addAttribute("unavailableItems", unavailableItems);
        model.addAttribute("recentOrders", recent.getContent());
        model.addAttribute("currentWait", currentWait);
        return "admin/index";
    }
}