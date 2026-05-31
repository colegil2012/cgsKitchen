package com.celtech.solutions.cgsKitchen.models.storefront.menu;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "menu_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem {
    @Id private String id;
    private String name;
    private String description;
    private long priceCents;
    private String priceDisplay;

    /** References {@code categories._id}. */
    @Indexed
    private String categoryId;

    /** References {@code badges._id}. Nullable. */
    private String badgeId;

    /** References {@code option_groups._id}. */
    @Builder.Default
    private List<String> optionGroupIds = new ArrayList<>();

    @Builder.Default
    private Map<String, List<String>> defaultsByGroupId = new HashMap<>();

    private Integer prepTimeMinutes;

    private boolean available;
    private int sortOrder;
}