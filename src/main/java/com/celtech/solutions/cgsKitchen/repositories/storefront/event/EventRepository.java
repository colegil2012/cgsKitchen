package com.celtech.solutions.cgsKitchen.repositories.storefront.event;

import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends MongoRepository<Event, String> {

    /**
     * Find the currently active event (if any). At-most-one is enforced
     * by the service layer's activation logic; this returns the first
     * match in case of accidental duplicates so the storefront never
     * crashes from a data anomaly.
     */
    Optional<Event> findFirstByActiveTrue();

    /**
     * All events with active=true. Should normally be 0 or 1, but used
     * by the activation logic to deactivate any prior actives.
     */
    List<Event> findByActiveTrue();

    /**
     * The next scheduled event with start time after the given moment,
     * regardless of {@code active} flag. Used for "next event" display
     * when none is currently active.
     */
    Optional<Event> findFirstByStartAtGreaterThanOrderByStartAtAsc(Instant after);

    /** All events ending after the given moment, oldest first. */
    List<Event> findByEndAtGreaterThanOrderByStartAtAsc(Instant after);

    /** Admin listing — paginated, newest first by start time. */
    Page<Event> findAllByOrderByStartAtDesc(Pageable pageable);

    /**
     * Find any events whose end time has passed but {@code active} flag
     * is still true. Used by the deactivation sweeper to clean up
     * forgotten activations.
     */
    List<Event> findByActiveTrueAndEndAtLessThan(Instant cutoff);
}