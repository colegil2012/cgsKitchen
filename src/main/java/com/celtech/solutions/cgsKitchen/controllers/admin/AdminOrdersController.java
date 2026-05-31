package com.celtech.solutions.cgsKitchen.controllers.admin;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.repositories.user.UserRepository;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderTransitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin orders management page.
 *
 * <p>All status mutations route through {@link OrderTransitionService} so
 * the admin path gets the same side effects as the POS API: matrix
 * validation, audit log entry, delivery dispatch on IN_KITCHEN, and the
 * per-order lock. Before this refactor, admin transitions called
 * {@code OrderService.updateStatus} directly and skipped all of that.
 */
@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrdersController {

    private final OrderService orderService;
    private final OrderTransitionService transitions;
    private final OrderEventService orderEvents;
    private final UserRepository users;

    // -------------------- List with status filter --------------------

    @GetMapping
    public String list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean includePending,
            @RequestParam(defaultValue = "0") int page,
            Model model
    ) {
        Order.Status filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = Order.Status.valueOf(status);
            } catch (IllegalArgumentException ignored) {}
        }
        var pageReq = PageRequest.of(Math.max(page, 0), 25);
        var orders = orderService.findAdmin(filter, pageReq);

        model.addAttribute("orders", orders.getContent());
        model.addAttribute("currentStatus", status);
        model.addAttribute("includePending", includePending);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", orders.getTotalPages());
        model.addAttribute("statuses", Order.Status.values());
        return "admin/orders/list";
    }

    // -------------------- Detail --------------------

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model, RedirectAttributes redirect) {
        var order = orderService.findById(id).orElse(null);
        if (order == null) {
            redirect.addFlashAttribute("error", "Order not found.");
            return "redirect:/admin/orders";
        }
        model.addAttribute("order", order);
        model.addAttribute("user",
                order.getUserId() == null ? null : users.findById(order.getUserId()).orElse(null));
        model.addAttribute("statuses", Order.Status.values());
        model.addAttribute("events", orderEvents.historyFor(id));
        return "admin/orders/detail";
    }

    // -------------------- Update status --------------------

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable String id,
                               @RequestParam String status,
                               @RequestParam(required = false) String note,
                               Authentication auth,
                               RedirectAttributes redirect) {
        Order.Status target;
        try {
            target = Order.Status.valueOf(status);
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", "Unknown status: " + status);
            return "redirect:/admin/orders/" + id;
        }

        String actor = (auth == null) ? "admin" : auth.getName();
        var result = transitions.transition(id, target, "admin", actor, note);

        switch (result.outcome()) {
            case SUCCESS -> {
                String msg = "Order moved to " + result.order().getStatus() + ".";
                if (result.dispatched()) {
                    msg += " Courier dispatched (" + result.order().getDeliveryExternalId() + ").";
                }
                redirect.addFlashAttribute("notice", msg);
            }
            case NO_CHANGE -> redirect.addFlashAttribute("notice",
                    "Order already in " + target + ".");
            case REJECTED -> redirect.addFlashAttribute("error", result.message());
            case NOT_FOUND -> redirect.addFlashAttribute("error", "Order not found.");
        }
        return "redirect:/admin/orders/" + id;
    }

    // -------------------- Redispatch (when Uber cancels mid-flight) --------------------

    @PostMapping("/{id}/redispatch")
    public String redispatch(@PathVariable String id,
                             Authentication auth,
                             RedirectAttributes redirect) {
        String actor = (auth == null) ? "admin" : auth.getName();
        var result = transitions.redispatch(id, actor);

        switch (result.outcome()) {
            case SUCCESS -> {
                if (result.dispatched()) {
                    redirect.addFlashAttribute("notice",
                            "New courier dispatched (" + result.order().getDeliveryExternalId() + ").");
                } else {
                    redirect.addFlashAttribute("error",
                            "Dispatch failed — see logs.");
                }
            }
            case REJECTED -> redirect.addFlashAttribute("error", result.message());
            case NOT_FOUND -> redirect.addFlashAttribute("error", "Order not found.");
            case NO_CHANGE -> { /* unreachable for redispatch */ }
        }
        return "redirect:/admin/orders/" + id;
    }
}