package com.celtech.solutions.cgsKitchen.models.storefront.menu.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable group of {@link OptionChoice}s — e.g. "Cheese", "Meat",
 * "Veggies". Menu items reference groups by id so changing a group
 * (adding a new cheese, taking lamb offline) updates every dish that
 * uses it.
 */
@Document(collection = "option_groups")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionGroup {

    /** Stable id used in form submissions and from MenuItem references, e.g. "cheese". */
    @Id
    private String id;

    /** Human-friendly label, e.g. "Cheese". */
    private String label;

    private SelectionType selectionType;

    /** Only meaningful for SINGLE: must the customer pick one? */
    private boolean required;

    /** Only meaningful for MULTI: cap on selections (0 = no cap). */
    private int maxSelections;

    /** Ordered list of choice ids referenced from {@code option_choices}. */
    @Builder.Default
    private List<String> choiceIds = new ArrayList<>();

    @Builder.Default
    private boolean available = true;
    private String unavailableReason;

    /**
     * Id of the choice that should be pre-selected on the menu card and
     * used on quick-add. Must appear in {@link #choiceIds} (validated at
     * assembly time). May be null for purely optional groups.
     */
    private String defaultChoiceId;

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;

    public enum SelectionType { SINGLE, MULTI }
}