package com.celtech.solutions.cgsKitchen.models.user;

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
 * A saved delivery address belonging to a {@link User}.
 *
 * <p>Kept in its own collection (rather than embedded) so a customer
 * can have several and edit them independently.
 */
@Document(collection = "addresses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    @Id
    private String id;

    @Indexed
    private String userId;

    /** "Home", "Work" — optional friendly tag. */
    private String label;

    private String line1;
    private String line2;
    private String city;
    private String state;
    private String postalCode;
    private String country;

    /** Optional delivery hints — "Leave at door", "Buzz 4B", etc. */
    private String notes;

    @Builder.Default
    private boolean primary = false;

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    /** Single-line display, e.g. for order receipts. */
    public String toSingleLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(line1);
        if (line2 != null && !line2.isBlank()) sb.append(", ").append(line2);
        sb.append(", ").append(city).append(", ").append(state).append(' ').append(postalCode);
        return sb.toString();
    }
}