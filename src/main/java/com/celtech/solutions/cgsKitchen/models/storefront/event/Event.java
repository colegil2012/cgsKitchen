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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * A scheduled appearance of the food truck — at a specific venue, on a
 * specific date, within a specific time window.
 *
 * <p>An event is either <b>one-time</b> ({@code recurrence == null}) or
 * <b>recurring</b> ({@code recurrence != null}). For one-time events,
 * {@code startAt} and {@code endAt} are the concrete time window for
 * the single appearance. For recurring events, those fields represent
 * the <i>currently activated</i> occurrence — they're stamped fresh
 * each time the owner activates the event (per-week activation; see
 * {@link com.celtech.solutions.cgsKitchen.services.storefront.event.EventService#activate}).
 *
 * <p>The {@link #recurrence} sub-document describes the weekly pattern:
 * day-of-week, time-of-day window, and an optional effective range.
 * Calendar generation uses this to compute future occurrences without
 * creating individual rows for each week.
 *
 * <p><b>Activation semantics:</b> {@code active} flag is operator intent
 * — "we are set up and serving right now." The storefront only treats
 * the event as "live" when {@code active==true} AND
 * {@code now ∈ [startAt, endAt]}. Recurring events behave identically —
 * the activation method stamps {@code startAt}/{@code endAt} for the
 * current week's slot, so the same dual gate applies.
 */
@Document(collection = "events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @Id
    private String id;

    /** Human-readable title — "Saturday Night at Mile Wide Brewery". */
    private String title;

    /** Free-form description for customer-facing display. */
    private String description;

    /** Venue address. Embedded value type — not linked to user addresses. */
    private EventAddress address;

    /**
     * Start of the currently activated (or one-time) occurrence. UTC.
     * For recurring events, this is set per-activation to the start of
     * the current week's slot. Null for unactivated recurring events
     * that have never been activated.
     */
    @Indexed
    private Instant startAt;

    /** End of the currently activated (or one-time) occurrence. UTC. */
    @Indexed
    private Instant endAt;

    /** Operator-confirmed "we are here and open". */
    @Indexed
    private boolean active;

    /**
     * If present, this is a recurring event. The rule describes the
     * weekly pattern; activation derives the per-week
     * {@link #startAt}/{@link #endAt} from this rule. Null for
     * one-time events.
     */
    private RecurrenceRule recurrence;

    /** Categorization. Multi-tagged events are allowed. */
    private Set<Tag> tags;

    /** Internal notes for the operator — not customer-facing. */
    private String internalNotes;

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    /** Convenience — true if this is a recurring (weekly-pattern) event. */
    public boolean isRecurring() {
        return recurrence != null;
    }

    /**
     * Categorization tags for events. Multi-select.
     */
    public enum Tag {
        /** Single appearance, not part of a recurring schedule. */
        ONE_TIME,
        /** Part of a regular weekly/monthly schedule. */
        RECURRING,
        /** Festival, market, or other event with multiple food vendors. */
        MULTI_VENDOR,
        /** Booked event, not open to the general public. */
        PRIVATE,
        /** Off-site catering — not at a public venue. */
        CATERING
    }

    /**
     * Venue address. Distinct from user delivery addresses — venues have
     * names, sometimes lack apartment-level precision, may be temporary
     * locations.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventAddress {
        /** Optional venue name — "Mile Wide Brewery", "Bardstown Road Festival". */
        private String venueName;
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String postalCode;
        private String country;

        /**
         * Single-line representation for the banner.
         * "Mile Wide Brewery, 422 Baxter Ave, Louisville KY"
         */
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

    /**
     * Weekly recurrence rule. One DB row, many calendar occurrences.
     *
     * <p>Why weekly only: for a food truck operation, "every Tuesday
     * 5-10pm" covers virtually all scheduling cases. Monthly-on-second-
     * Tuesday or "every other Friday" can be added later if needed.
     *
     * <p>Times are interpreted in the storefront's configured timezone
     * (see {@code app.storefront.timezone}). The recurrence rule itself
     * is timezone-naive: "Tuesday 5pm" means 5pm local time, regardless
     * of DST shifts. {@code effectiveFrom} and {@code effectiveUntil}
     * are absolute UTC instants — they answer "when does the schedule
     * start/end."
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecurrenceRule {
        /** Which day of the week the event happens. */
        private DayOfWeek dayOfWeek;

        /** Local time the event starts (in storefront timezone). */
        private LocalTime startTime;

        /** Local time the event ends (in storefront timezone). */
        private LocalTime endTime;

        /**
         * First day this schedule is in effect (UTC instant, typically
         * midnight in storefront timezone). Null = effective immediately.
         */
        private Instant effectiveFrom;

        /**
         * Last day this schedule is in effect (UTC instant). Null = forever.
         * When set, calendar generation stops producing occurrences after
         * this point.
         */
        private Instant effectiveUntil;

        /**
         * Human-readable summary — "Tuesdays 5:00 PM – 10:00 PM".
         * Used in admin lists and customer calendar.
         */
        public String describe() {
            if (dayOfWeek == null) return "Recurring";
            String day = dayOfWeek.toString().charAt(0)
                    + dayOfWeek.toString().substring(1).toLowerCase() + "s";
            if (startTime == null || endTime == null) return day;
            return day + " " + fmt(startTime) + " – " + fmt(endTime);
        }

        private static String fmt(LocalTime t) {
            int hour = t.getHour();
            int min = t.getMinute();
            String ampm = hour >= 12 ? "PM" : "AM";
            int displayHour = hour % 12;
            if (displayHour == 0) displayHour = 12;
            return min == 0
                    ? displayHour + " " + ampm
                    : String.format("%d:%02d %s", displayHour, min, ampm);
        }
    }
}