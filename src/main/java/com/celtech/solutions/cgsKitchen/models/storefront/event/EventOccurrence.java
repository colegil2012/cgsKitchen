package com.celtech.solutions.cgsKitchen.models.storefront.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A single occurrence of an event in time — either a one-time event
 * (where there's exactly one occurrence per Event row), or one of many
 * computed occurrences from a recurring event's rule.
 *
 * <p>Used by the calendar generation logic: the service expands
 * recurring events into individual {@code EventOccurrence}s within a
 * date range, then sorts them chronologically alongside one-time event
 * occurrences for display.
 *
 * <p>The {@code event} reference is the source Event document. Multiple
 * occurrences may share the same Event (recurring case). The
 * {@code recurring} flag tells the UI whether to show the "every
 * Tuesday" hint.
 */
public record EventOccurrence(
        Event event,
        LocalDate date,
        Instant startAt,
        Instant endAt,
        boolean recurring
) implements Comparable<EventOccurrence> {

    @Override
    public int compareTo(EventOccurrence other) {
        return this.startAt.compareTo(other.startAt);
    }

    /** True if this occurrence is happening right now. */
    public boolean isCurrent(Instant now) {
        return !now.isBefore(startAt) && !now.isAfter(endAt);
    }

    /** True if this occurrence is in the past. */
    public boolean isPast(Instant now) {
        return endAt.isBefore(now);
    }
}