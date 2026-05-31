package com.celtech.solutions.cgsKitchen.services.storefront.event;

import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import com.celtech.solutions.cgsKitchen.models.storefront.event.EventOccurrence;
import com.celtech.solutions.cgsKitchen.repositories.storefront.event.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Event lifecycle service.
 *
 * <p>Core responsibilities:
 * <ul>
 *   <li>CRUD — create, update, delete events (one-time + recurring)</li>
 *   <li>Activation — flip an event to active, computing this week's
 *       slot for recurring events</li>
 *   <li>"Currently open" determination — active flag AND now ∈ [start, end]</li>
 *   <li>Auto-deactivation sweeper for forgotten activations</li>
 *   <li>"Next event" lookup that considers both one-time and recurring</li>
 *   <li>Calendar generation — expand recurring rules into occurrences
 *       within a date range</li>
 * </ul>
 *
 * <p><b>Recurring event activation:</b> when the owner activates a
 * recurring event, this service computes the "current week's slot"
 * (today's instance if we're in it, otherwise the upcoming instance)
 * and stamps {@code startAt}/{@code endAt} accordingly. All
 * downstream logic ({@code findCurrentlyOpen}, banner display,
 * auto-deactivation) then works identically to one-time events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    /** Auto-deactivation sweep frequency. */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    private final EventRepository events;

    // ================================================================
    //  Read
    // ================================================================

    public Optional<Event> findCurrentlyOpen() {
        Optional<Event> active = events.findFirstByActiveTrue();
        if (active.isEmpty()) return Optional.empty();
        Event e = active.get();
        Instant now = Instant.now();
        if (e.getStartAt() == null || e.getEndAt() == null) return Optional.empty();
        if (now.isBefore(e.getStartAt()) || now.isAfter(e.getEndAt())) {
            return Optional.empty();
        }
        return Optional.of(e);
    }

    /**
     * The next scheduled occurrence — considers both:
     * <ul>
     *   <li>One-time events whose {@code startAt} is in the future</li>
     *   <li>Recurring events whose next computed occurrence is in the future</li>
     * </ul>
     * Returns the Event whose next occurrence is soonest. For display
     * purposes (banner "next: Tuesday"), the Event is enough — the
     * customer-facing calendar uses the richer {@link #expandOccurrences}.
     */
    public Optional<Event> findNextScheduled(ZoneId zone) {
        Instant now = Instant.now();
        Optional<Event> oneTime = events
                .findFirstByStartAtGreaterThanOrderByStartAtAsc(now);

        // Recurring events: compute the next occurrence for each
        Optional<Event> recurring = events.findAll().stream()
                .filter(Event::isRecurring)
                .map(e -> new RecurringNext(e, computeNextOccurrenceStart(e, now, zone)))
                .filter(rn -> rn.nextStart != null)
                .min(Comparator.comparing(rn -> rn.nextStart))
                .map(rn -> rn.event);

        // Pick whichever is earlier
        if (oneTime.isEmpty()) return recurring;
        if (recurring.isEmpty()) return oneTime;

        Instant oneTimeStart = oneTime.get().getStartAt();
        Instant recurringStart = computeNextOccurrenceStart(recurring.get(), now, zone);
        return recurringStart != null && recurringStart.isBefore(oneTimeStart)
                ? recurring
                : oneTime;
    }

    public Optional<Event> findById(String id) {
        return events.findById(id);
    }

    public Page<Event> findAll(Pageable pageable) {
        return events.findAllByOrderByStartAtDesc(pageable);
    }

    public List<Event> findUpcomingAndCurrent() {
        return events.findByEndAtGreaterThanOrderByStartAtAsc(Instant.now());
    }

    // ================================================================
    //  Calendar / occurrence expansion
    // ================================================================

    /**
     * Expand all events into concrete occurrences within a date range.
     * One-time events contribute at most one occurrence each (if their
     * {@code startAt} falls in the range). Recurring events contribute
     * one occurrence per matching day-of-week in the range, bounded by
     * their {@code effectiveFrom}/{@code effectiveUntil}.
     *
     * <p>Results are sorted chronologically by start instant.
     */
    public List<EventOccurrence> expandOccurrences(LocalDate from, LocalDate to, ZoneId zone) {
        Instant rangeStart = from.atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.atTime(23, 59, 59).atZone(zone).toInstant();

        List<EventOccurrence> result = new ArrayList<>();

        for (Event e : events.findAll()) {
            if (e.isRecurring()) {
                result.addAll(expandRecurring(e, rangeStart, rangeEnd, zone));
            } else if (e.getStartAt() != null
                    && !e.getStartAt().isBefore(rangeStart)
                    && !e.getStartAt().isAfter(rangeEnd)) {
                LocalDate date = e.getStartAt().atZone(zone).toLocalDate();
                result.add(new EventOccurrence(e, date, e.getStartAt(), e.getEndAt(), false));
            }
        }

        result.sort(Comparator.naturalOrder());
        return result;
    }

    /**
     * Compute all occurrences of a recurring event within [start, end].
     * Each occurrence becomes a separate EventOccurrence record sharing
     * the same underlying Event reference.
     */
    private List<EventOccurrence> expandRecurring(Event e, Instant rangeStart, Instant rangeEnd, ZoneId zone) {
        Event.RecurrenceRule rule = e.getRecurrence();
        if (rule == null || rule.getDayOfWeek() == null
                || rule.getStartTime() == null || rule.getEndTime() == null) {
            return List.of();
        }

        // Determine the effective range, clipped by rangeStart/rangeEnd
        Instant ruleStart = rule.getEffectiveFrom() == null
                ? rangeStart
                : (rule.getEffectiveFrom().isAfter(rangeStart) ? rule.getEffectiveFrom() : rangeStart);
        Instant ruleEnd = rule.getEffectiveUntil() == null
                ? rangeEnd
                : (rule.getEffectiveUntil().isBefore(rangeEnd) ? rule.getEffectiveUntil() : rangeEnd);

        if (ruleStart.isAfter(ruleEnd)) return List.of();

        // Find the first matching day-of-week >= ruleStart
        LocalDate cursor = ruleStart.atZone(zone).toLocalDate()
                .with(TemporalAdjusters.nextOrSame(rule.getDayOfWeek()));

        LocalDate endDate = ruleEnd.atZone(zone).toLocalDate();

        List<EventOccurrence> occurrences = new ArrayList<>();
        while (!cursor.isAfter(endDate)) {
            ZonedDateTime occStart = cursor.atTime(rule.getStartTime()).atZone(zone);
            ZonedDateTime occEnd = cursor.atTime(rule.getEndTime()).atZone(zone);

            // Handle end-time before start-time (overnight events) — bump end to next day
            if (occEnd.isBefore(occStart)) {
                occEnd = occEnd.plusDays(1);
            }

            Instant startInstant = occStart.toInstant();
            Instant endInstant = occEnd.toInstant();

            // Clip by the effective range (in case rule started/ended mid-week)
            if (!endInstant.isBefore(ruleStart) && !startInstant.isAfter(ruleEnd)) {
                occurrences.add(new EventOccurrence(e, cursor, startInstant, endInstant, true));
            }

            cursor = cursor.plusWeeks(1);
        }
        return occurrences;
    }

    /**
     * Compute the start instant of the next occurrence of a recurring
     * event, on or after the given moment. Returns null if the rule
     * has expired ({@code effectiveUntil} passed).
     */
    private Instant computeNextOccurrenceStart(Event e, Instant now, ZoneId zone) {
        if (!e.isRecurring()) return null;
        Event.RecurrenceRule rule = e.getRecurrence();
        if (rule == null || rule.getDayOfWeek() == null || rule.getStartTime() == null) return null;
        if (rule.getEffectiveUntil() != null && rule.getEffectiveUntil().isBefore(now)) return null;

        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate nextMatching = today.with(TemporalAdjusters.nextOrSame(rule.getDayOfWeek()));
        ZonedDateTime candidate = nextMatching.atTime(rule.getStartTime()).atZone(zone);

        // If today's slot has already started, skip to next week
        if (candidate.toInstant().isBefore(now)) {
            // Check if we're currently IN today's slot — if so, return its start
            ZonedDateTime todayEnd = nextMatching.atTime(rule.getEndTime()).atZone(zone);
            if (todayEnd.isBefore(candidate)) todayEnd = todayEnd.plusDays(1);
            if (todayEnd.toInstant().isAfter(now)) {
                // We're in today's slot — return its start (in the past)
                return candidate.toInstant();
            }
            nextMatching = nextMatching.plusWeeks(1);
            candidate = nextMatching.atTime(rule.getStartTime()).atZone(zone);
        }

        // Respect effectiveFrom
        if (rule.getEffectiveFrom() != null && candidate.toInstant().isBefore(rule.getEffectiveFrom())) {
            LocalDate startDay = rule.getEffectiveFrom().atZone(zone).toLocalDate()
                    .with(TemporalAdjusters.nextOrSame(rule.getDayOfWeek()));
            candidate = startDay.atTime(rule.getStartTime()).atZone(zone);
        }

        return candidate.toInstant();
    }

    // ================================================================
    //  Write
    // ================================================================

    public Event create(Event event) {
        event.setActive(false);
        return events.save(event);
    }

    public Event update(Event event) {
        Event existing = events.findById(event.getId()).orElseThrow();
        event.setActive(existing.isActive());
        event.setCreatedAt(existing.getCreatedAt());
        return events.save(event);
    }

    public void delete(String id) {
        events.deleteById(id);
    }

    /**
     * Mark an event active. For one-time events, just flips the flag.
     * For recurring events, computes "this week's occurrence" and stamps
     * {@code startAt}/{@code endAt} from that, so all the existing
     * window-based logic works unchanged.
     *
     * <p>Deactivates any other active event first (single-active invariant).
     */
    public Event activate(String id, ZoneId zone) {
        Event target = events.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Event not found: " + id));

        Instant now = Instant.now();

        // For recurring events, compute and stamp this week's window.
        if (target.isRecurring()) {
            Instant nextStart = computeNextOccurrenceStart(target, now, zone);
            if (nextStart == null) {
                throw new IllegalStateException(
                        "Cannot activate recurring event — schedule has expired");
            }
            Event.RecurrenceRule rule = target.getRecurrence();
            ZonedDateTime startZdt = nextStart.atZone(zone);
            ZonedDateTime endZdt = startZdt.toLocalDate().atTime(rule.getEndTime()).atZone(zone);
            if (endZdt.isBefore(startZdt)) endZdt = endZdt.plusDays(1);

            target.setStartAt(nextStart);
            target.setEndAt(endZdt.toInstant());
            log.info("Stamped recurring event {} with this-week window {} – {}",
                    target.getId(), target.getStartAt(), target.getEndAt());
        } else {
            if (target.getEndAt() != null && target.getEndAt().isBefore(now)) {
                throw new IllegalStateException(
                        "Cannot activate event whose end time has passed");
            }
        }

        // Deactivate any other actives.
        events.findByActiveTrue().stream()
                .filter(e -> !e.getId().equals(id))
                .forEach(e -> {
                    e.setActive(false);
                    events.save(e);
                    log.info("Deactivated event {} (replaced by {})", e.getId(), id);
                });

        target.setActive(true);
        Event saved = events.save(target);
        log.info("Activated event {} ({})", saved.getId(), saved.getTitle());
        return saved;
    }

    public Event deactivate(String id) {
        Event e = events.findById(id).orElseThrow();
        e.setActive(false);
        Event saved = events.save(e);
        log.info("Deactivated event {} ({})", saved.getId(), saved.getTitle());
        return saved;
    }

    // ================================================================
    //  Auto-deactivation sweeper
    // ================================================================

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = SWEEP_INTERVAL_MS)
    public void autoDeactivateExpired() {
        List<Event> expired = events.findByActiveTrueAndEndAtLessThan(Instant.now());
        if (expired.isEmpty()) return;
        for (Event e : expired) {
            e.setActive(false);
            events.save(e);
            log.info("Auto-deactivated expired event {} ({}, ended {})",
                    e.getId(), e.getTitle(), e.getEndAt());
        }
    }

    // ================================================================
    //  Internal types
    // ================================================================

    private record RecurringNext(Event event, Instant nextStart) {}
}