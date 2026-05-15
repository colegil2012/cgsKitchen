package com.celtech.solutions.cgsKitchen.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "menu_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem {
    @Id private String id;
    @Indexed private String clientId;
    private String name;
    private String description;
    private long priceCents;
    private String priceDisplay;
    private String category;       // tacos, plates, sides, drinks
    private String badge;          // optional ("Most loved", "Limited")
    private boolean available;
    private int sortOrder;
}
