package com.celtech.solutions.cgsKitchen.controllers.admin;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.repositories.user.AddressRepository;
import com.celtech.solutions.cgsKitchen.repositories.user.PaymentMethodRepository;
import com.celtech.solutions.cgsKitchen.repositories.user.UserRepository;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUsersController {

    private final UserRepository users;
    private final AddressRepository addresses;
    private final PaymentMethodRepository paymentMethods;
    private final OrderService orders;

    // -------------------- List --------------------

    @GetMapping
    public String list(@RequestParam(required = false) String q, Model model) {
        List<User> list;
        if (q == null || q.isBlank()) {
            list = users.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        } else {
            // simple email/name contains filter
            String needle = q.trim().toLowerCase();
            list = users.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                    .filter(u -> (u.getEmail() != null && u.getEmail().toLowerCase().contains(needle))
                            || (u.getDisplayName() != null && u.getDisplayName().toLowerCase().contains(needle)))
                    .toList();
        }
        model.addAttribute("users", list);
        model.addAttribute("q", q);
        return "admin/users/list";
    }

    // -------------------- Detail --------------------

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model, RedirectAttributes redirect) {
        var user = users.findById(id).orElse(null);
        if (user == null) {
            redirect.addFlashAttribute("error", "User not found.");
            return "redirect:/admin/users";
        }
        model.addAttribute("user", user);
        model.addAttribute("addresses",
                addresses.findByUserIdOrderByPrimaryDescUpdatedAtDesc(user.getId()));
        model.addAttribute("paymentMethods",
                paymentMethods.findByUserIdOrderByDefaultMethodDescUpdatedAtDesc(user.getId()));
        model.addAttribute("orders", orders.findByUserIdOrderByCreatedAtDesc(user.getId()));
        model.addAttribute("allRoles", User.Role.values());
        return "admin/users/detail";
    }

    // -------------------- Toggle enabled --------------------

    @PostMapping("/{id}/toggle-enabled")
    public String toggleEnabled(@PathVariable String id, RedirectAttributes redirect) {
        var user = users.findById(id).orElse(null);
        if (user == null) {
            redirect.addFlashAttribute("error", "User not found.");
            return "redirect:/admin/users";
        }
        user.setEnabled(!user.isEnabled());
        users.save(user);
        redirect.addFlashAttribute("notice",
                user.getEmail() + (user.isEnabled() ? " — enabled." : " — disabled."));
        return "redirect:/admin/users/" + id;
    }

    // -------------------- Update roles --------------------

    @PostMapping("/{id}/roles")
    public String updateRoles(
            @PathVariable String id,
            @RequestParam(required = false) List<String> roles,
            RedirectAttributes redirect
    ) {
        var user = users.findById(id).orElse(null);
        if (user == null) {
            redirect.addFlashAttribute("error", "User not found.");
            return "redirect:/admin/users";
        }
        Set<User.Role> newRoles = EnumSet.noneOf(User.Role.class);
        if (roles != null) {
            for (String r : roles) {
                try { newRoles.add(User.Role.valueOf(r)); } catch (IllegalArgumentException ignored) {}
            }
        }
        if (newRoles.isEmpty()) newRoles.add(User.Role.CUSTOMER); // never strip all roles
        user.setRoles(newRoles);
        users.save(user);
        redirect.addFlashAttribute("notice", "Roles updated.");
        return "redirect:/admin/users/" + id;
    }
}