package com.celtech.solutions.cgsKitchen.repositories.storefront.event;

import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends MongoRepository<Event, String> {

    /**
     * The currently active event (operator-open), if any. At-most-one is
     * enforced by the activation logic; this returns the first match in
     * case of a data anomaly so nothing crashes.
     */
    Optional<Event> findFirstByActiveTrue();

    /** All events with active=true. Normally 0 or 1. */
    List<Event> findByActiveTrue();

    /**
     * The next one-time/concrete event whose start is after the given
     * moment, regardless of active flag. Used alongside series projection
     * for "next event" display.
     */
    Optional<Event> findFirstByStartAtGreaterThanOrderByStartAtAsc(Instant after);

    /** All events ending after the given moment, oldest first. */
    List<Event> findByEndAtGreaterThanOrderByStartAtAsc(Instant after);

    /** Concrete future events within a window — for calendar merge. */
    List<Event> findByStartAtBetweenOrderByStartAtAsc(Instant from, Instant to);

    /** Admin listing — paginated, newest first by start time. */
    Page<Event> findAllByOrderByStartAtDesc(Pageable pageable);

    /**
     * Events whose end time has passed but are still active. Used by the
     * auto-close sweeper with a grace-adjusted cutoff (endAt + graceHours).
     */
    List<Event> findByActiveTrueAndEndAtLessThan(Instant cutoff);

    // ---- Series occurrence support ----

    /**
     * An existing occurrence of a series on a specific local date. Used
     * to prevent materializing two Events for the same series+day.
     */
    Optional<Event> findBySeriesIdAndOccurrenceDate(String seriesId, LocalDate occurrenceDate);

    /** All materialized occurrences of a series, newest first. */
    List<Event> findBySeriesIdOrderByStartAtDesc(String seriesId);

    // ---- Admin events page: three regions ----

    /**
     * One-time events (no series) that are current or upcoming —
     * end time still in the future. Drives the "One-time events" region.
     * Active one-time events have endAt in the future too, so they
     * appear here until they end.
     */
    List<Event> findBySeriesIdIsNullAndEndAtGreaterThanOrderByStartAtAsc(Instant after);

    /**
     * Past events — ended and not active — newest first, paginated.
     * Includes BOTH one-time events and materialized recurring
     * occurrences; each is its own row, so recurring appearances break
     * down per-date. Backs the "Past events" region + stats drill-in.
     */
    Page<Event> findByActiveFalseAndEndAtLessThanOrderByStartAtDesc(Instant cutoff, Pageable pageable);
}