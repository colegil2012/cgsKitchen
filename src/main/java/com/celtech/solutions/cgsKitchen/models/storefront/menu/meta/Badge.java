package com.celtech.solutions.cgsKitchen.models.storefront.menu.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Marketing label shown on a menu card — "Top Seller", "New", etc.
 *
 * <p>{@code color} is a token consumed by the CSS so design changes
 * don't require data migrations.
 */
@Document(collection = "badges")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Badge {
    @Id
    private String id;
    private String label;
    private String color;
}