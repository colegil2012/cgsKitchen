package com.celtech.solutions.cgsKitchen.models.storefront.menu.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A single selectable option — "Cheddar", "Lamb", "Mint sauce".
 *
 * <p>Lives in its own collection so options can be CRUD'd, audited, and
 * reused across multiple {@link OptionGroup}s without duplicating data.
 */
@Document(collection = "option_choices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionChoice {

    /** Stable id used in form submissions, e.g. "cheddar". */
    @Id
    private String id;

    /** Human-friendly label, e.g. "Cheddar". */
    private String label;

    /** Price upcharge (or discount, if negative) in cents. */
    private long priceDeltaCents;

    /** Globally available? Use this to take an option offline (e.g. ran out of lamb). */
    @Builder.Default
    private boolean available = true;
    private String unavailableReason;

    /**
     * Free-form tag for organization, e.g. "cheese", "meat", "veggie".
     * Useful for admin filters and reporting. Not enforced.
     */
    @Indexed
    private String tag;

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
}