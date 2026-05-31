
package com.celtech.solutions.cgsKitchen.models.storefront.menu.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A menu section — "Sandwiches", "Drinks", etc.
 *
 * <p>{@code id} is the URL-safe slug used in anchors and form
 * submissions. {@code name} is the display label.
 */
@Document(collection = "categories")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    @Id
    private String id;
    private String name;
    private int sortOrder;

    @Builder.Default
    private int defaultPrepTimeMinutes = 5;
}