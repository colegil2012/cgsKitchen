package com.celtech.solutions.cgsKitchen.services.storefront.menu;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.Badge;
import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.Category;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.MenuItem;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.view.MenuItemView;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionChoice;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionGroup;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.BadgeRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.CategoryRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.MenuItemRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.OptionChoiceRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.OptionGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuItemRepository menuItems;
    private final OptionGroupRepository optionGroups;
    private final OptionChoiceRepository optionChoices;
    private final CategoryRepository categories;
    private final BadgeRepository badges;

    // ---------- Lookup ----------

    public List<MenuItemView> findAll() {
        return assemble(menuItems.findAll(Sort.by("sortOrder", "name")));
    }

    public List<MenuItemView> findAvailable() {
        return assemble(menuItems.findByAvailableTrue(Sort.by("sortOrder", "name")));
    }

    public Optional<MenuItemView> findById(String id) {
        return menuItems.findById(id).map(item -> assemble(List.of(item)).get(0));
    }

    /**
     * Groups items by their category, ordered by the category's sortOrder.
     * Key is the {@link Category} so the template gets both id and display name.
     */
    public Map<Category, List<MenuItemView>> findGroupedByCategory() {
        List<MenuItemView> available = findAvailable();
        Map<String, Category> byId = categories.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));

        // Preserve category sortOrder in the LinkedHashMap.
        Map<Category, List<MenuItemView>> grouped = new LinkedHashMap<>();
        byId.values().stream()
                .sorted(Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName))
                .forEach(c -> grouped.put(c, new ArrayList<>()));

        for (MenuItemView item : available) {
            Category cat = byId.get(item.getCategoryId());
            if (cat == null) {
                log.warn("MenuItem '{}' references missing category '{}'", item.getId(), item.getCategoryId());
                continue;
            }
            grouped.get(cat).add(item);
        }

        // Drop empty categories so the menu page doesn't show ghost sections.
        grouped.values().removeIf(List::isEmpty);
        return grouped;
    }

    // ---------- Assembly ----------

    private List<MenuItemView> assemble(List<MenuItem> items) {
        if (items.isEmpty()) return List.of();

        // Categories
        List<String> categoryIds = items.stream()
                .map(MenuItem::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<String, Category> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : categories.findAllById(categoryIds).stream()
                  .collect(Collectors.toMap(Category::getId, Function.identity()));

        // Badges
        List<String> badgeIds = items.stream()
                .map(MenuItem::getBadgeId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<String, Badge> badgesById = badgeIds.isEmpty()
                ? Map.of()
                : badges.findAllById(badgeIds).stream()
                  .collect(Collectors.toMap(Badge::getId, Function.identity()));

        // Option groups
        List<String> groupIds = items.stream()
                .flatMap(i -> i.getOptionGroupIds() == null ? Stream.empty() : i.getOptionGroupIds().stream())
                .distinct().toList();
        Map<String, OptionGroup> groupsById = groupIds.isEmpty()
                ? Map.of()
                : optionGroups.findAllById(groupIds).stream()
                  .collect(Collectors.toMap(OptionGroup::getId, Function.identity()));

        // Option choices
        List<String> choiceIds = groupsById.values().stream()
                .flatMap(g -> g.getChoiceIds() == null ? Stream.empty() : g.getChoiceIds().stream())
                .distinct().toList();
        Map<String, OptionChoice> choicesById = choiceIds.isEmpty()
                ? Map.of()
                : optionChoices.findAllById(choiceIds).stream()
                  .collect(Collectors.toMap(OptionChoice::getId, Function.identity()));

        return items.stream()
                .map(item -> toView(item, categoriesById, badgesById, groupsById, choicesById))
                .toList();
    }

    private MenuItemView toView(MenuItem item,
                                Map<String, Category> categoriesById,
                                Map<String, Badge> badgesById,
                                Map<String, OptionGroup> groupsById,
                                Map<String, OptionChoice> choicesById) {
        Category cat = item.getCategoryId() == null ? null : categoriesById.get(item.getCategoryId());
        Badge badge  = item.getBadgeId()    == null ? null : badgesById.get(item.getBadgeId());

        List<MenuItemView.OptionGroupView> groupViews = new ArrayList<>();
        if (item.getOptionGroupIds() != null) {
            for (String gid : item.getOptionGroupIds()) {
                OptionGroup g = groupsById.get(gid);
                if (g == null) {
                    log.warn("MenuItem '{}' references missing option group '{}'", item.getId(), gid);
                    continue;
                }
                if (!g.isAvailable()) {
                    // Entire group 86'd — drop it from the view.
                    continue;
                }
                // Per-item override, falls back to group's defaultChoiceId.
                List<String> itemDefaults = item.getDefaultsByGroupId() == null
                        ? null
                        : item.getDefaultsByGroupId().get(gid);
                groupViews.add(toGroupView(g, choicesById, itemDefaults));
            }
        }

        return MenuItemView.builder()
                .id(item.getId())
                .name(item.getName())
                .description(item.getDescription())
                .priceCents(item.getPriceCents())
                .priceDisplay(item.getPriceDisplay())
                .categoryId(item.getCategoryId())
                .categoryName(cat == null ? item.getCategoryId() : cat.getName())
                .badgeId(item.getBadgeId())
                .badgeLabel(badge == null ? null : badge.getLabel())
                .badgeColor(badge == null ? null : badge.getColor())
                .available(item.isAvailable())
                .sortOrder(item.getSortOrder())
                .optionGroups(groupViews)
                .build();
    }

    private MenuItemView.OptionGroupView toGroupView(OptionGroup g,
                                                     Map<String, OptionChoice> choicesById,
                                                     List<String> itemDefaultChoiceIds) {
        // Build the choice views first so we can re-derive defaults if any
        // configured default is 86'd.
        List<MenuItemView.OptionChoiceView> choiceViews = new ArrayList<>();
        if (g.getChoiceIds() != null) {
            for (String cid : g.getChoiceIds()) {
                OptionChoice c = choicesById.get(cid);
                if (c == null) {
                    log.warn("OptionGroup '{}' references missing choice '{}'", g.getId(), cid);
                    continue;
                }
                choiceViews.add(MenuItemView.OptionChoiceView.builder()
                        .id(c.getId())
                        .label(c.getLabel())
                        .priceDeltaCents(c.getPriceDeltaCents())
                        .available(c.isAvailable())
                        .unavailableReason(c.isAvailable() ? null : c.getUnavailableReason())
                        .defaultChoice(false) // set below
                        .build());
            }
        }

        // Compute defaults — strictly from *available* choices.
        Set<String> availableChoiceIds = choiceViews.stream()
                .filter(MenuItemView.OptionChoiceView::isAvailable)
                .map(MenuItemView.OptionChoiceView::getId)
                .collect(Collectors.toSet());

        Set<String> defaultIds = new LinkedHashSet<>();
        if (itemDefaultChoiceIds != null && !itemDefaultChoiceIds.isEmpty()) {
            for (String id : itemDefaultChoiceIds) {
                if (availableChoiceIds.contains(id)) defaultIds.add(id);
            }
        } else if (g.getDefaultChoiceId() != null
                && availableChoiceIds.contains(g.getDefaultChoiceId())) {
            defaultIds.add(g.getDefaultChoiceId());
        }

        // SINGLE: if no default survived 86'ing, pick the first available
        // choice so the dropdown still has a sensible pre-selection.
        if (g.getSelectionType() == OptionGroup.SelectionType.SINGLE
                && defaultIds.isEmpty() && !availableChoiceIds.isEmpty()) {
            choiceViews.stream()
                    .filter(MenuItemView.OptionChoiceView::isAvailable)
                    .findFirst()
                    .ifPresent(c -> defaultIds.add(c.getId()));
        }

        // Apply the resolved defaults back onto the views.
        for (MenuItemView.OptionChoiceView v : choiceViews) {
            v.setDefaultChoice(defaultIds.contains(v.getId()));
        }

        return MenuItemView.OptionGroupView.builder()
                .id(g.getId())
                .label(g.getLabel())
                .selectionType(g.getSelectionType())
                .required(g.isRequired())
                .maxSelections(g.getMaxSelections())
                .available(g.isAvailable())
                .choices(choiceViews)
                .build();
    }

    // ---------- Resolution (form submission -> cart selections) ----------

    public Resolved resolveSelections(MenuItemView item, Map<String, List<String>> submitted) {
        List<Cart.SelectedOption> resolved = new ArrayList<>();
        long upcharge = 0L;

        List<MenuItemView.OptionGroupView> groups = item.getOptionGroups() == null
                ? List.of() : item.getOptionGroups();

        for (MenuItemView.OptionGroupView group : groups) {
            List<String> submittedPicks = submitted == null
                    ? List.of() : submitted.getOrDefault(group.getId(), List.of());
            List<String> picks = new ArrayList<>(submittedPicks);

            if (group.getSelectionType() == OptionGroup.SelectionType.SINGLE) {
                if (picks.size() > 1) {
                    throw new IllegalArgumentException(
                            "Group '" + group.getId() + "' accepts only one choice.");
                }
                if (picks.isEmpty()) {
                    MenuItemView.OptionChoiceView fallback = group.getChoices().stream()
                            .filter(c -> c != null && c.isAvailable()
                                    && c.isDefaultChoice() && c.getId() != null)
                            .findFirst().orElse(null);
                    if (fallback != null) {
                        picks.add(fallback.getId());
                    } else if (group.isRequired()) {
                        // Check whether the group has *any* available choices.
                        boolean anyAvailable = group.getChoices().stream()
                                .anyMatch(c -> c != null && c.isAvailable());
                        if (!anyAvailable) {
                            throw new IllegalArgumentException(
                                    group.getLabel() + " is currently unavailable.");
                        }
                        throw new IllegalArgumentException(
                                "Please choose a " + group.getLabel() + ".");
                    } else {
                        continue;
                    }
                }
            } else {
                if (group.getMaxSelections() > 0
                        && picks.size() > group.getMaxSelections()) {
                    throw new IllegalArgumentException(
                            "You can pick at most " + group.getMaxSelections()
                                    + " for " + group.getLabel() + ".");
                }
                // Quick-add path for MULTI: if nothing was submitted (customer
                // didn't expand Customize), use whichever choices were flagged
                // as defaults in the view.
                if (picks.isEmpty()) {
                    group.getChoices().stream()
                            .filter(c -> c != null && c.isAvailable()
                                    && c.isDefaultChoice() && c.getId() != null)
                            .map(MenuItemView.OptionChoiceView::getId)
                            .forEach(picks::add);
                }
            }

            for (String choiceId : picks) {
                if (choiceId == null || choiceId.isBlank()) continue;
                MenuItemView.OptionChoiceView choice = group.getChoices().stream()
                        .filter(c -> c != null && c.isAvailable() && choiceId.equals(c.getId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown choice '" + choiceId + "' for " + group.getLabel()));
                resolved.add(new Cart.SelectedOption(
                        group.getId(), group.getLabel(),
                        choice.getId(), choice.getLabel(),
                        choice.getPriceDeltaCents()
                ));
                upcharge += choice.getPriceDeltaCents();
            }
        }

        return new Resolved(resolved, item.getPriceCents() + upcharge);
    }

    public record Resolved(List<Cart.SelectedOption> selections, long unitPriceCents) {}
}