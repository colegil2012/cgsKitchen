package com.celtech.solutions.cgsKitchen.models.storefront.event;

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
import java.time.LocalDate;
import java.util.Set;

/**
 * A concrete, dated appearance of the food truck — at a specific venue,
 * on a specific date, within a specific time window.
 *
 * <p><b>Series/occurrence split:</b> an Event is always a concrete
 * occurrence. Recurring schedules live in {@link EventSeries}; activating
 * a series materializes a concrete Event here with {@link #seriesId} set
 * and the series' title/address/that-day's-window snapshotted on. One-time
 * events are created directly with {@code seriesId == null}.
 *
 * <p><b>Two notions of "open":</b>
 * <ul>
 *   <li>{@link #isOpenForCustomers(Instant)} — {@code active} AND now is
 *       within [startAt, endAt] AND {@link #onlineOrderingOpen}. Gates the
 *       storefront banner and online checkout. Customers cannot order
 *       outside the window, nor when online ordering is switched off.</li>
 *   <li>{@link #isShiftOpen()} — just {@code active}. The operator's till
 *       is open; POS/walk-in orders attach regardless of the window OR the
 *       online-ordering switch. This is what keeps walk-in service and
 *       late offline flush working when online ordering is off.</li>
 * </ul>
 *
 * <p><b>Online-ordering switch:</b> {@link #onlineOrderingOpen} is a
 * one-way subtractive gate. It defaults true; set false at creation for a
 * private/walk-in-only event, or toggled false live by the operator (POS
 * or admin) to stop taking web orders while POS keeps running. It can only
 * <em>close</em> online ordering — it never forces ordering open outside
 * the active window.
 *
 * <p><b>Single-active invariant:</b> at most one Event is {@code active}.
 */
@Document(collection = "events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @Id
    private String id;

    /**
     * Back-reference to the {@link EventSeries} this occurrence was
     * materialized from. Null for one-time events. Indexed.
     */
    @Indexed
    private String seriesId;

    /**
     * The local calendar date of this appearance. Set when materialized
     * from a series; for one-time events, derived from startAt.
     */
    private LocalDate occurrenceDate;

    /** Human-readable title. */
    private String title;

    /** Free-form description for customer-facing display. */
    private String description;

    /** Venue address. Embedded value type — snapshot, not a live link. */
    private EventAddress address;

    /** Start of this occurrence's window. UTC. Always populated. */
    @Indexed
    private Instant startAt;

    /** End of this occurrence's window. UTC. Always populated. */
    @Indexed
    private Instant endAt;

    /** Operator-confirmed "we are here and serving / till is open". */
    @Indexed
    private boolean active;

    /**
     * Whether customers may place online orders for this appearance.
     * Defaults true. False = private/walk-in-only, or operator switched
     * web ordering off. Subtractive only: does not affect POS/walk-in
     * order-taking, which keys off {@link #isShiftOpen()}.
     */
    @Builder.Default
    private boolean onlineOrderingOpen = true;

    /** Categorization. Multi-tagged events are allowed. */
    private Set<Tag> tags;

    /** Internal notes for the operator — not customer-facing. */
    private String internalNotes;

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    /** True if this occurrence came from a recurring series. */
    public boolean isFromSeries() {
        return seriesId != null && !seriesId.isBlank();
    }

    /**
     * Customer-open: storefront shows "open now" and online checkout is
     * permitted only when this is true. Requires the active flag, the
     * current moment within the window, AND online ordering switched on.
     */
    public boolean isOpenForCustomers(Instant now) {
        return active
                && onlineOrderingOpen
                && startAt != null && endAt != null
                && !now.isBefore(startAt) && !now.isAfter(endAt);
    }

    /**
     * Operator-open: the shift/till is open. True whenever the event is
     * active, regardless of the window OR the online-ordering switch — so
     * walk-in/POS service and late offline flush keep working even with
     * online ordering off.
     */
    public boolean isShiftOpen() {
        return active;
    }

    public enum Tag {
        ONE_TIME,
        RECURRING,
        MULTI_VENDOR,
        PRIVATE,
        CATERING
    }

    /**
     * Venue address. Referenced by both {@link Event} (snapshot) and
     * {@link EventSeries} (template).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventAddress {
        private String venueName;
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String postalCode;
        private String country;

        public String toSingleLine() {
            StringBuilder sb = new StringBuilder();
            if (venueName != null && !venueName.isBlank()) {
                sb.append(venueName);
                if (line1 != null) sb.append(", ");
            }
            if (line1 != null) sb.append(line1);
            if (line2 != null && !line2.isBlank()) sb.append(", ").append(line2);
            if (city != null) sb.append(", ").append(city);
            if (state != null) sb.append(' ').append(state);
            return sb.toString();
        }
    }
}