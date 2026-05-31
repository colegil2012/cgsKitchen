package com.celtech.solutions.cgsKitchen.models.storefront.menu.view;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.MenuItem;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-side projection of a {@link MenuItem} with its category, badge,
 * option groups, and choices fully assembled. Used by Thymeleaf so
 * templates render display names without extra fetches.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItemView {
    private String id;
    private String name;
    private String description;
    private long priceCents;
    private String priceDisplay;

    /** Slug — useful for anchors / hidden form fields. */
    private String categoryId;
    /** Display name from the categories collection. */
    private String categoryName;

    /** Slug — nullable. */
    private String badgeId;
    /** Display label from the badges collection — nullable. */
    private String badgeLabel;
    /** CSS color token — nullable. */
    private String badgeColor;

    private boolean available;
    private int sortOrder;

    @Builder.Default
    private List<OptionGroupView> optionGroups = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionGroupView {
        private String id;
        private String label;
        private OptionGroup.SelectionType selectionType;
        private boolean required;
        private boolean available;
        private String unavailableReason;
        private int maxSelections;

        @Builder.Default
        private List<OptionChoiceView> choices = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionChoiceView {
        private String id;
        private String label;
        private long priceDeltaCents;
        private boolean available;
        private String unavailableReason;
        private boolean defaultChoice;
    }
}