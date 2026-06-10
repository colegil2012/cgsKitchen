package com.celtech.solutions.cgsKitchen.services.storefront.event;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import com.celtech.solutions.cgsKitchen.models.storefront.event.EventOccurrence;
import com.celtech.solutions.cgsKitchen.models.storefront.event.EventSeries;
import com.celtech.solutions.cgsKitchen.repositories.storefront.event.EventRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.event.EventSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Event lifecycle service for the series/occurrence model.
 *
 * <p><b>Model:</b> recurring schedules live in {@link EventSeries};
 * concrete dated appearances are {@link Event}s. Activating a series
 * materializes a concrete Event for the current slot (Philosophy 1 —
 * on-demand, not pre-generated). One-time events are concrete Events
 * created directly.
 *
 * <p><b>Two notions of open</b> (see {@link Event}):
 * <ul>
 *   <li>customer-open — {@link #findCurrentlyOpen()}: active AND in window.
 *       Gates storefront + online checkout.</li>
 *   <li>operator-open (shift) — {@link #findActiveShift()}: active, any time.
 *       Late offline POS orders attach here.</li>
 * </ul>
 *
 * <p><b>Single-active invariant:</b> activation is rejected while any
 * event is operator-open — the operator must close the current shift
 * first ({@link ShiftOpenException}). This guarantees flushed offline
 * orders attach to exactly one unambiguous event.
 *
 * <p><b>Auto-close safety net:</b> the sweeper closes a forgotten active
 * event only after {@code endAt + app.events.auto-close-grace-hours},
 * not at {@code endAt} — so an operator can keep the till open past the
 * scheduled window while offline payments flush.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    /** Sweep frequency for the forgotten-activation safety net. */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    private final EventRepository events;
    private final EventSeriesRepository series;
    private final AppProperties props;

    // ================================================================
    //  Read — customer-open vs operator-open
    // ================================================================

    /**
     * The customer-open event: active AND now within [startAt, endAt].
     * Drives the storefront banner and the online-checkout gate.
     */
    public Optional<Event> findCurrentlyOpen() {
        Optional<Event> active = events.findFirstByActiveTrue();
        if (active.isEmpty()) return Optional.empty();
        Event e = active.get();
        return e.isOpenForCustomers(Instant.now()) ? Optional.of(e) : Optional.empty();
    }

    /**
     * The operator-open shift: the active event regardless of window.
     * Drives POS "shift open" state and offline-order attachment. Differs
     * from {@link #findCurrentlyOpen()} only during the post-{@code endAt}
     * flush period (active, but past the customer window).
     */
    public Optional<Event> findActiveShift() {
        return events.findFirstByActiveTrue();
    }

    public Optional<Event> findById(String id) {
        return events.findById(id);
    }

    public Optional<EventSeries> findSeriesById(String id) {
        return series.findById(id);
    }

    public Page<Event> findAll(Pageable pageable) {
        return events.findAllByOrderByStartAtDesc(pageable);
    }

    public Page<EventSeries> findAllSeries(Pageable pageable) {
        return series.findAllByOrderByCreatedAtDesc(pageable);
    }

    public List<Event> findUpcomingAndCurrent() {
        return events.findByEndAtGreaterThanOrderByStartAtAsc(Instant.now());
    }

    public List<Event> findOccurrencesOf(String seriesId) {
        return events.findBySeriesIdOrderByStartAtDesc(seriesId);
    }

    /**
     * One-time events (no series) that are current or upcoming — for the
     * admin events page "One-time events" region.
     */
    public List<Event> findOneTimeUpcoming(Instant now) {
        return events.findBySeriesIdIsNullAndEndAtGreaterThanOrderByStartAtAsc(now);
    }

    /**
     * Past events — ended and inactive — newest first, paginated. Backs the
     * admin "Past events" region and the stats drill-in. Includes both
     * one-time events and materialized recurring occurrences.
     */
    public org.springframework.data.domain.Page<Event> findPastEvents(
            Instant now, Pageable pageable) {
        return events.findByActiveFalseAndEndAtLessThanOrderByStartAtDesc(now, pageable);
    }

    /**
     * The projected start instant of a series' next occurrence, for callers
     * that need to gate on timing before activating (e.g. the POS
     * lead-window check). Null if the series is unknown or expired.
     */
    public Instant projectedNextStartForSeries(String seriesId, ZoneId zone) {
        return series.findById(seriesId)
                .map(s -> computeNextOccurrenceStart(s, Instant.now(), zone))
                .orElse(null);
    }

    // ================================================================
    //  Next scheduled — concrete events + series projection
    // ================================================================

    /**
     * The next scheduled appearance start, considering both concrete
     * future events and the next projected occurrence of each in-service
     * series. Returns the Event if the soonest is a concrete one;
     * otherwise projects the series' next occurrence into a transient
     * (unsaved) Event for display. For the POS/banner "next" hint.
     *
     * <p>NOTE: the returned Event for a series projection is NOT persisted
     * — it's a display projection. Activating it goes through
     * {@link #activateSeriesOccurrence}, which creates the real row.
     */
    public Optional<Event> findNextScheduled(ZoneId zone) {
        Instant now = Instant.now();

        Optional<Event> concreteNext =
                events.findFirstByStartAtGreaterThanOrderByStartAtAsc(now);

        Optional<ProjectedOccurrence> seriesNext = series.findAll().stream()
                .filter(s -> s.isInService(now))
                .map(s -> new ProjectedOccurrence(s, computeNextOccurrenceStart(s, now, zone)))
                .filter(p -> p.start != null)
                .min(Comparator.comparing(p -> p.start));

        if (concreteNext.isEmpty() && seriesNext.isEmpty()) return Optional.empty();
        if (seriesNext.isEmpty()) return concreteNext;
        if (concreteNext.isEmpty()) {
            return Optional.of(projectToTransientEvent(seriesNext.get(), zone));
        }

        // Both present — pick the earlier.
        Instant concreteStart = concreteNext.get().getStartAt();
        if (seriesNext.get().start.isBefore(concreteStart)) {
            return Optional.of(projectToTransientEvent(seriesNext.get(), zone));
        }
        return concreteNext;
    }

    // ================================================================
    //  Calendar expansion — concrete events + series projection, deduped
    // ================================================================

    /**
     * Expand all appearances into concrete occurrences within a date
     * range: persisted concrete Events plus projected occurrences from
     * in-service series. Where a series occurrence falls on the same date
     * as an already-materialized concrete Event for that series, the
     * concrete row wins (dedup) — so an activated occurrence isn't
     * double-listed against its own projection.
     *
     * <p>Results sorted chronologically by start instant.
     *
     * <p><b>Storefront wiring deferred:</b> this method is built for the
     * eventual customer calendar; the storefront template consuming it is
     * a later chunk. POS/admin use the simpler shift/next lookups above.
     */
    public List<EventOccurrence> expandOccurrences(LocalDate from, LocalDate to, ZoneId zone) {
        Instant rangeStart = from.atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.atTime(23, 59, 59).atZone(zone).toInstant();

        List<EventOccurrence> result = new ArrayList<>();

        // 1) Concrete events whose start falls in range.
        List<Event> concrete = events.findByStartAtBetweenOrderByStartAtAsc(rangeStart, rangeEnd);
        var claimed = new HashSet<String>();  // "seriesId|date" already represented by a concrete row
        for (Event e : concrete) {
            LocalDate d = e.getStartAt().atZone(zone).toLocalDate();
            result.add(new EventOccurrence(e, d, e.getStartAt(), e.getEndAt(), e.isFromSeries()));
            if (e.isFromSeries()) {
                claimed.add(e.getSeriesId() + "|" + d);
            }
        }

        // 2) Series projections, skipping dates already claimed by a concrete row.
        for (EventSeries s : series.findAll()) {
            for (ProjectedOccurrence p : expandSeries(s, rangeStart, rangeEnd, zone)) {
                LocalDate d = p.start.atZone(zone).toLocalDate();
                if (claimed.contains(s.getId() + "|" + d)) continue;
                Event transient_ = projectToTransientEvent(p, zone);
                result.add(new EventOccurrence(transient_, d, transient_.getStartAt(),
                        transient_.getEndAt(), true));
            }
        }

        result.sort(Comparator.naturalOrder());
        return result;
    }

    // ================================================================
    //  Write — series CRUD delegated to EventSeriesService; here we do
    //  one-time event CRUD + activation/materialization.
    // ================================================================

    /** Create a one-time concrete event (seriesId stays null). */
    public Event createOneTime(Event event) {
        event.setActive(false);
        event.setSeriesId(null);
        if (event.getStartAt() != null) {
            // occurrenceDate convenience — derived from the window start.
            event.setOccurrenceDate(event.getStartAt().atZone(zoneOf()).toLocalDate());
        }
        return events.save(event);
    }

    /** Update a one-time concrete event, preserving active + createdAt. */
    public Event updateOneTime(Event event) {
        Event existing = events.findById(event.getId()).orElseThrow();
        event.setActive(existing.isActive());
        event.setCreatedAt(existing.getCreatedAt());
        event.setSeriesId(existing.getSeriesId());
        if (event.getStartAt() != null) {
            event.setOccurrenceDate(event.getStartAt().atZone(zoneOf()).toLocalDate());
        }
        return events.save(event);
    }

    public void deleteEvent(String id) {
        events.deleteById(id);
    }

    /**
     * Activate a one-time concrete event. Enforces reject-while-open and
     * the "window not already past" guard.
     */
    public Event activateOneTimeEvent(String eventId) {
        Event target = events.findById(eventId).orElseThrow(
                () -> new IllegalArgumentException("Event not found: " + eventId));

        guardNoOpenShift(eventId);

        Instant now = Instant.now();
        if (target.getEndAt() != null && target.getEndAt().isBefore(now)) {
            throw new IllegalStateException("Cannot activate event whose end time has passed");
        }

        target.setActive(true);
        Event saved = events.save(target);
        log.info("Activated one-time event {} ({})", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * Activate a recurring series for its current slot: materialize (or
     * reuse) a concrete Event for this occurrence's date and activate it.
     * Enforces reject-while-open.
     *
     * <p>Reuse-by-day: if an Event already exists for this (seriesId,
     * occurrenceDate) — e.g. the operator closed it earlier today and is
     * reopening — that same row is reactivated rather than creating a
     * second, so the day's orders stay on one occurrence.
     */
    public Event activateSeriesOccurrence(String seriesId, ZoneId zone) {
        EventSeries s = series.findById(seriesId).orElseThrow(
                () -> new IllegalArgumentException("Series not found: " + seriesId));

        guardNoOpenShift(null);

        Instant now = Instant.now();
        Instant nextStart = computeNextOccurrenceStart(s, now, zone);
        if (nextStart == null) {
            throw new IllegalStateException(
                    "Cannot activate series — schedule has expired or has no upcoming slot");
        }

        EventSeries.RecurrenceRule rule = s.getRecurrence();
        ZonedDateTime startZdt = nextStart.atZone(zone);
        LocalDate occDate = startZdt.toLocalDate();

        // Find the window matching the occurrence's day-of-week to get its
        // end time. computeNextOccurrenceStart already picked a real window,
        // so a match must exist; guard defensively regardless.
        EventSeries.DayWindow window = rule.windowFor(occDate.getDayOfWeek());
        if (window == null || window.getEndTime() == null) {
            throw new IllegalStateException(
                    "No scheduled window for " + occDate.getDayOfWeek() + " in this series");
        }
        ZonedDateTime endZdt = occDate.atTime(window.getEndTime()).atZone(zone);
        if (endZdt.isBefore(startZdt)) endZdt = endZdt.plusDays(1);
        final Instant endInstant = endZdt.toInstant();

        // Reuse an existing occurrence for this series+day if present.
        Event occurrence = events.findBySeriesIdAndOccurrenceDate(seriesId, occDate)
                .orElseGet(() -> materializeOccurrence(s, nextStart, endInstant, occDate));

        occurrence.setActive(true);
        Event saved = events.save(occurrence);
        log.info("Activated series {} occurrence {} for {} ({} – {})",
                seriesId, saved.getId(), occDate, saved.getStartAt(), saved.getEndAt());
        return saved;
    }

    /**
     * Close the open shift (explicit operator action — the normal
     * end-of-shift path). Idempotent.
     */
    public Event deactivate(String id) {
        Event e = events.findById(id).orElseThrow();
        e.setActive(false);
        Event saved = events.save(e);
        log.info("Closed shift / deactivated event {} ({})", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * Set the online-ordering switch on a concrete event (live operator
     * toggle from POS or admin, or an explicit set). Idempotent. This is
     * the subtractive customer-ordering gate: turning it off stops web
     * orders ({@link Event#isOpenForCustomers}) while leaving walk-in/POS
     * order-taking ({@link Event#isShiftOpen}) untouched.
     */
    public Event setOnlineOrdering(String eventId, boolean enabled) {
        Event e = events.findById(eventId).orElseThrow(
                () -> new IllegalArgumentException("Event not found: " + eventId));
        e.setOnlineOrderingOpen(enabled);
        Event saved = events.save(e);
        log.info("Event {} ({}) online ordering → {}",
                saved.getId(), saved.getTitle(), enabled ? "OPEN" : "CLOSED");
        return saved;
    }

    // ================================================================
    //  Auto-close safety net (grace period past endAt)
    // ================================================================

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = SWEEP_INTERVAL_MS)
    public void autoCloseForgottenShifts() {
        long graceHours = props.events() == null ? 6 : props.events().autoCloseGraceHours();
        Instant cutoff = Instant.now().minus(Duration.ofHours(Math.max(0, graceHours)));
        List<Event> stale = events.findByActiveTrueAndEndAtLessThan(cutoff);
        if (stale.isEmpty()) return;
        for (Event e : stale) {
            e.setActive(false);
            events.save(e);
            log.info("Auto-closed forgotten shift {} ({}); ended {} (grace {}h)",
                    e.getId(), e.getTitle(), e.getEndAt(), graceHours);
        }
    }

    // ================================================================
    //  Internals
    // ================================================================

    /**
     * Reject activation if any event is currently operator-open (active),
     * other than the one being (re)activated. The operator must close the
     * current shift first.
     */
    private void guardNoOpenShift(String allowEventId) {
        for (Event open : events.findByActiveTrue()) {
            if (allowEventId != null && open.getId().equals(allowEventId)) continue;
            throw new ShiftOpenException(open);
        }
    }

    /** Build (but don't activate) a concrete occurrence from a series. */
    private Event materializeOccurrence(EventSeries s, Instant startAt, Instant endAt, LocalDate date) {
        return Event.builder()
                .seriesId(s.getId())
                .occurrenceDate(date)
                .title(s.getTitle())
                .description(s.getDescription())
                .internalNotes(s.getInternalNotes())
                .address(snapshotAddress(s.getAddress()))
                .tags(s.getTags() == null ? null : new HashSet<>(s.getTags()))
                .startAt(startAt)
                .endAt(endAt)
                .active(false)
                .onlineOrderingOpen(s.isDefaultOnlineOrderingOpen())
                .build();
    }

    /** Defensive copy of the series address so later series edits don't mutate history. */
    private static Event.EventAddress snapshotAddress(Event.EventAddress a) {
        if (a == null) return null;
        return Event.EventAddress.builder()
                .venueName(a.getVenueName())
                .line1(a.getLine1())
                .line2(a.getLine2())
                .city(a.getCity())
                .state(a.getState())
                .postalCode(a.getPostalCode())
                .country(a.getCountry())
                .build();
    }

    /**
     * Compute the start instant of the next occurrence of a series, on or
     * after {@code now} — the soonest across ALL per-day windows. Returns
     * null if the rule has expired or has no windows.
     */
    private Instant computeNextOccurrenceStart(EventSeries s, Instant now, ZoneId zone) {
        EventSeries.RecurrenceRule rule = s.getRecurrence();
        if (rule == null || rule.getWindows() == null || rule.getWindows().isEmpty()) return null;
        if (rule.getEffectiveUntil() != null && rule.getEffectiveUntil().isBefore(now)) return null;

        Instant soonest = null;
        for (EventSeries.DayWindow w : rule.getWindows()) {
            Instant candidate = nextStartForWindow(w, rule, now, zone);
            if (candidate == null) continue;
            if (soonest == null || candidate.isBefore(soonest)) soonest = candidate;
        }
        return soonest;
    }

    /**
     * Next start instant for one day-window, on or after {@code now},
     * honoring the rule's effectiveFrom. Mirrors the legacy single-day
     * logic (including "are we mid-slot today" → return today's start).
     */
    private Instant nextStartForWindow(EventSeries.DayWindow w, EventSeries.RecurrenceRule rule,
                                       Instant now, ZoneId zone) {
        if (w.getDayOfWeek() == null || w.getStartTime() == null || w.getEndTime() == null) return null;

        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate nextMatching = today.with(TemporalAdjusters.nextOrSame(w.getDayOfWeek()));
        ZonedDateTime candidate = nextMatching.atTime(w.getStartTime()).atZone(zone);

        if (candidate.toInstant().isBefore(now)) {
            ZonedDateTime todayEnd = nextMatching.atTime(w.getEndTime()).atZone(zone);
            if (todayEnd.isBefore(candidate)) todayEnd = todayEnd.plusDays(1);
            if (todayEnd.toInstant().isAfter(now)) {
                return candidate.toInstant();  // mid-slot today
            }
            nextMatching = nextMatching.plusWeeks(1);
            candidate = nextMatching.atTime(w.getStartTime()).atZone(zone);
        }

        if (rule.getEffectiveFrom() != null && candidate.toInstant().isBefore(rule.getEffectiveFrom())) {
            LocalDate startDay = rule.getEffectiveFrom().atZone(zone).toLocalDate()
                    .with(TemporalAdjusters.nextOrSame(w.getDayOfWeek()));
            candidate = startDay.atTime(w.getStartTime()).atZone(zone);
        }

        return candidate.toInstant();
    }

    /** All projected occurrences of a series within [rangeStart, rangeEnd],
     *  across every per-day window. */
    private List<ProjectedOccurrence> expandSeries(EventSeries s, Instant rangeStart,
                                                   Instant rangeEnd, ZoneId zone) {
        EventSeries.RecurrenceRule rule = s.getRecurrence();
        if (rule == null || rule.getWindows() == null || rule.getWindows().isEmpty()) {
            return List.of();
        }

        Instant ruleStart = rule.getEffectiveFrom() == null
                ? rangeStart
                : (rule.getEffectiveFrom().isAfter(rangeStart) ? rule.getEffectiveFrom() : rangeStart);
        Instant ruleEnd = rule.getEffectiveUntil() == null
                ? rangeEnd
                : (rule.getEffectiveUntil().isBefore(rangeEnd) ? rule.getEffectiveUntil() : rangeEnd);
        if (ruleStart.isAfter(ruleEnd)) return List.of();

        LocalDate endDate = ruleEnd.atZone(zone).toLocalDate();
        List<ProjectedOccurrence> out = new ArrayList<>();

        for (EventSeries.DayWindow w : rule.getWindows()) {
            if (w.getDayOfWeek() == null || w.getStartTime() == null || w.getEndTime() == null) continue;

            LocalDate cursor = ruleStart.atZone(zone).toLocalDate()
                    .with(TemporalAdjusters.nextOrSame(w.getDayOfWeek()));

            while (!cursor.isAfter(endDate)) {
                ZonedDateTime occStart = cursor.atTime(w.getStartTime()).atZone(zone);
                ZonedDateTime occEnd = cursor.atTime(w.getEndTime()).atZone(zone);
                if (occEnd.isBefore(occStart)) occEnd = occEnd.plusDays(1);

                Instant startInstant = occStart.toInstant();
                Instant endInstant = occEnd.toInstant();
                if (!endInstant.isBefore(ruleStart) && !startInstant.isAfter(ruleEnd)) {
                    out.add(new ProjectedOccurrence(s, startInstant, endInstant));
                }
                cursor = cursor.plusWeeks(1);
            }
        }
        return out;
    }

    /** Project a series occurrence into a transient (unsaved) Event for display. */
    private Event projectToTransientEvent(ProjectedOccurrence p, ZoneId zone) {
        EventSeries s = p.series;
        Instant endAt = p.end;
        if (endAt == null && s.getRecurrence() != null) {
            ZonedDateTime startZdt = p.start.atZone(zone);
            EventSeries.DayWindow w = s.getRecurrence().windowFor(startZdt.getDayOfWeek());
            if (w != null && w.getEndTime() != null) {
                ZonedDateTime endZdt = startZdt.toLocalDate().atTime(w.getEndTime()).atZone(zone);
                if (endZdt.isBefore(startZdt)) endZdt = endZdt.plusDays(1);
                endAt = endZdt.toInstant();
            }
        }
        return Event.builder()
                .id(null)                       // transient — not persisted
                .seriesId(s.getId())
                .occurrenceDate(p.start.atZone(zone).toLocalDate())
                .title(s.getTitle())
                .description(s.getDescription())
                .address(s.getAddress())
                .tags(s.getTags())
                .startAt(p.start)
                .endAt(endAt)
                .active(false)
                .onlineOrderingOpen(s.isDefaultOnlineOrderingOpen())
                .build();
    }

    private ZoneId zoneOf() {
        String tz = props.storefront().timezone();
        return ZoneId.of(tz == null || tz.isBlank() ? "America/New_York" : tz);
    }

    private record ProjectedOccurrence(EventSeries series, Instant start, Instant end) {
        ProjectedOccurrence(EventSeries series, Instant start) { this(series, start, null); }
    }

    /**
     * Thrown when activation is attempted while another event is
     * operator-open. Carries the open event so callers can craft a
     * "close the current shift first" message.
     */
    public static class ShiftOpenException extends IllegalStateException {
        private final transient Event openEvent;
        public ShiftOpenException(Event openEvent) {
            super("A shift is already open (" +
                    (openEvent.getTitle() == null ? openEvent.getId() : openEvent.getTitle()) +
                    "). Close the current shift before activating another.");
            this.openEvent = openEvent;
        }
        public Event getOpenEvent() { return openEvent; }
    }
}