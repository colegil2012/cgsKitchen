package com.celtech.solutions.cgsKitchen.controllers.admin;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.MenuItem;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.MenuItemRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.BadgeRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.CategoryRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.OptionGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/menu")
@RequiredArgsConstructor
public class AdminMenuController {

    private final MenuItemRepository menuItems;
    private final CategoryRepository categories;
    private final BadgeRepository badges;
    private final OptionGroupRepository optionGroups;


    // -------------------- List --------------------

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items",
                menuItems.findAll(Sort.by("sortOrder", "name")));
        model.addAttribute("categories", categories.findAll());
        model.addAttribute("badges", badges.findAll());
        return "admin/menu/list";
    }

    // -------------------- Edit / Create form --------------------

    @GetMapping("/new")
    public String newItem(Model model) {
        model.addAttribute("item", MenuItem.builder().available(true).build());
        populateFormChoices(model);
        return "admin/menu/edit";
    }

    @GetMapping("/{id}")
    public String editItem(@PathVariable String id, Model model,
                           RedirectAttributes redirect) {
        var item = menuItems.findById(id).orElse(null);
        if (item == null) {
            redirect.addFlashAttribute("error", "Menu item not found.");
            return "redirect:/admin/menu";
        }
        model.addAttribute("item", item);
        populateFormChoices(model);
        return "admin/menu/edit";
    }

    private void populateFormChoices(Model model) {
        model.addAttribute("categories", categories.findAll());
        model.addAttribute("badges", badges.findAll());
        model.addAttribute("allGroups", optionGroups.findAll());
    }

    // -------------------- Save --------------------

    @PostMapping("/save")
    public String save(
            @RequestParam(required = false) String id,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam long priceCents,
            @RequestParam(required = false) String priceDisplay,
            @RequestParam String categoryId,
            @RequestParam(required = false) String badgeId,
            @RequestParam(required = false) List<String> optionGroupIds,
            @RequestParam(defaultValue = "false") boolean available,
            @RequestParam(defaultValue = "0") int sortOrder,
            @RequestParam(required = false) int prepTimeMinutes,
            RedirectAttributes redirect
    ) {
        MenuItem item;
        if (id == null || id.isBlank()) {
            // New — id derived from a slugged name unless caller supplies one
            String slug = name.trim().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-|-$", "");
            item = menuItems.findById(slug).orElseGet(() ->
                    MenuItem.builder().id(slug).build());
        } else {
            item = menuItems.findById(id).orElseThrow();
        }

        item.setName(name.trim());
        item.setDescription(description == null ? "" : description.trim());
        item.setPriceCents(priceCents);
        item.setPriceDisplay(priceDisplay == null || priceDisplay.isBlank()
                ? String.format("$%.2f", priceCents / 100.0)
                : priceDisplay.trim());
        item.setCategoryId(categoryId);
        item.setBadgeId(badgeId == null || badgeId.isBlank() ? null : badgeId);
        item.setOptionGroupIds(optionGroupIds == null ? List.of() : optionGroupIds);
        item.setAvailable(available);
        item.setSortOrder(sortOrder);
        item.setPrepTimeMinutes(prepTimeMinutes);

        menuItems.save(item);
        redirect.addFlashAttribute("notice", "Saved \"" + item.getName() + "\".");
        return "redirect:/admin/menu";
    }

    // -------------------- 86 toggle (quick action from list page) --------------------

    @PostMapping("/{id}/toggle-availability")
    public String toggleAvailability(@PathVariable String id,
                                     RedirectAttributes redirect) {
        var item = menuItems.findById(id).orElse(null);
        if (item == null) {
            redirect.addFlashAttribute("error", "Menu item not found.");
            return "redirect:/admin/menu";
        }
        item.setAvailable(!item.isAvailable());
        menuItems.save(item);
        redirect.addFlashAttribute("notice",
                item.getName() + (item.isAvailable() ? " — back on the menu." : " — 86'd."));
        return "redirect:/admin/menu";
    }

    // -------------------- Delete --------------------

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) {
        menuItems.findById(id).ifPresent(item -> menuItems.deleteById(id));
        redirect.addFlashAttribute("notice", "Item removed.");
        return "redirect:/admin/menu";
    }
}