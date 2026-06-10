package com.celtech.solutions.cgsKitchen.controllers.api;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * POS-facing event status + activation, reworked for the series/occurrence
 * model.
 *
 * <p><b>Shift-live indicator</b> uses operator-open ({@code active} flag),
 * not the customer window — so the POS shows the shift as open while late
 * offline payments flush after the scheduled end. Whether customers can
 * order online is a separate customer-open computation, surfaced as
 * {@code customerOrderingOpen} for the POS to display if it cares.
 *
 * <p><b>Activation</b> routes by entity type:
 * <ul>
 *   <li>{@code POST /api/events/{id}/activate} — a one-time concrete event</li>
 *   <li>{@code POST /api/series/{id}/activate} — a series; materializes an
 *       occurrence for the current slot</li>
 * </ul>
 * Both enforce the single-active "close current shift first" rule
 * ({@link EventService.ShiftOpenException}) and the activation lead-window
 * gate (no earlier than {@code startAt - ACTIVATION_LEAD_MINUTES}).
 *
 * <p><b>"Next" candidate</b> in {@code status} may be a series projection
 * (a transient, unsaved Event with a null id but a populated
 * {@code seriesId}). The POS activates it via the series endpoint.
 */
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class PosEventController {

    private final EventService eventService;
    private final EventSummaryService eventSummaryService;
    private final AppProperties props;

    private ZoneId zone() {
        return ZoneId.of(props.storefront().timezone());
    }

    // ---------------- Read ----------------

    @GetMapping("/status")
    public EventStatusView status() {
        Instant now = Instant.now();
        ZoneId zone = zone();

        // Operator-open shift (active flag, regardless of window).
        Optional<Event> shift = eventService.findActiveShift();

        // Next candidate only matters when no shift is open.
        Optional<Event> next = shift.isPresent()
                ? Optional.empty()
                : eventService.findNextScheduled(zone);

        EventView shiftView = shift.map(e -> toView(e, now)).orElse(null);
        EventView nextView = next.map(e -> toView(e, now)).orElse(null);

        boolean customerOrderingOpen = shift
                .map(e -> e.isOpenForCustomers(now))
                .orElse(false);

        return new EventStatusView(
                shift.isPresent(),         // shiftLive (operator-open)
                customerOrderingOpen,      // customer window open right now
                shiftView,
                nextView);
    }

    // ---------------- Activation ----------------

    /** Activate a one-time concrete event. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activateEvent(@PathVariable String id) {
        Instant now = Instant.now();

        Event target = eventService.findById(id).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Event " + id + " not found"));
        }

        // Lead-window gate (concrete event already has a startAt).
        ResponseEntity<?> tooEarly = leadGate(target.getStartAt(), now);
        if (tooEarly != null) return tooEarly;

        try {
            Event activated = eventService.activateOneTimeEvent(id);
            return ResponseEntity.ok(toView(activated, now));
        } catch (EventService.ShiftOpenException e) {
            return ResponseEntity.status(409)
                    .body(new ErrorResponse("shift_open", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("activation_failed", e.getMessage()));
        }
    }

    /** Activate a series — materialize + activate the current-slot occurrence. */
    @PostMapping("/series/{id}/activate")
    public ResponseEntity<?> activateSeries(@PathVariable String id) {
        Instant now = Instant.now();
        ZoneId zone = zone();

        if (eventService.findSeriesById(id).isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Series " + id + " not found"));
        }

        // For the lead gate we need the projected start. findNextScheduled
        // gives us projections, but the simplest correct gate is to let the
        // service compute the slot and gate on the resulting occurrence —
        // however we want to reject BEFORE materializing if too early. So
        // compute the projected next start via a transient projection.
        Instant projectedStart = eventService.projectedNextStartForSeries(id, zone);
        ResponseEntity<?> tooEarly = leadGate(projectedStart, now);
        if (tooEarly != null) return tooEarly;

        try {
            Event occ = eventService.activateSeriesOccurrence(id, zone);
            return ResponseEntity.ok(toView(occ, now));
        } catch (EventService.ShiftOpenException e) {
            return ResponseEntity.status(409)
                    .body(new ErrorResponse("shift_open", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("activation_failed", e.getMessage()));
        }
    }

    /**
     * Close the open shift (operator action from the POS). Idempotent on
     * the server side via the service.
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<?> closeShift(@PathVariable String id) {
        try {
            Event closed = eventService.deactivate(id);
            return ResponseEntity.ok(toView(closed, Instant.now()));
        } catch (Exception e) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("close_failed", e.getMessage()));
        }
    }

    /**
     * Set online ordering on/off for a concrete event (live operator
     * toggle). Explicit set, idempotent. Turning it off stops web orders
     * while leaving POS/walk-in order-taking fully working.
     */
    @PostMapping("/{id}/online-ordering")
    public ResponseEntity<?> setOnlineOrdering(@PathVariable String id,
                                               @RequestBody OnlineOrderingRequest req) {
        if (req == null) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("bad_request", "Body with 'enabled' is required."));
        }
        try {
            Event updated = eventService.setOnlineOrdering(id, req.enabled());
            return ResponseEntity.ok(toView(updated, Instant.now()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("update_failed", e.getMessage()));
        }
    }

    public record OnlineOrderingRequest(boolean enabled) {}

    /**
     * Per-event sales summary — committed orders, totals by payment
     * method, items sold with modifier breakdown. Unchanged by the
     * series/occurrence rework: it summarizes a concrete event id, which
     * is exactly one appearance.
     */
    @GetMapping("/{id}/summary")
    public ResponseEntity<?> summary(@PathVariable String id) {
        if (eventService.findById(id).isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Event " + id + " not found"));
        }
        return ResponseEntity.ok(eventSummaryService.summarize(id));
    }

    // ---------------- Helpers ----------------

    /**
     * Returns a 400 "too_early" response if {@code start} is known and
     * {@code now} is before the lead window; null if the gate passes
     * (or start is unknown, in which case the service decides).
     */
    private ResponseEntity<?> leadGate(Instant start, Instant now) {
        if (start == null) return null;
        long activationLeadTimeMinutes =
                props.events() == null ? 15 : props.events().activationLeadTimeMinutes();
        Instant earliest = start.minusSeconds(activationLeadTimeMinutes * 60);
        if (now.isBefore(earliest)) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                    "too_early",
                    "Event cannot be activated until within "
                            + activationLeadTimeMinutes + " minutes of start."));
        }
        return null;
    }

    private EventView toView(Event e, Instant now) {
        boolean canActivate;
        long activationLeadTimeMinutes =
                props.events() == null ? 15 : props.events().activationLeadTimeMinutes();
        if (e.isActive()) {
            canActivate = false;
        } else if (e.getStartAt() != null) {
            Instant earliest = e.getStartAt().minusSeconds(activationLeadTimeMinutes * 60);
            canActivate = !now.isBefore(earliest);
        } else {
            canActivate = true; // unknown start (shouldn't happen post-rework)
        }

        return new EventView(
                e.getId(),                 // null for a series projection
                e.getSeriesId(),           // set when this is a series occurrence/projection
                e.getTitle(),
                e.getAddress() == null ? null : e.getAddress().toSingleLine(),
                e.getStartAt(),
                e.getEndAt(),
                e.isActive(),
                e.isFromSeries(),
                e.isOnlineOrderingOpen(),
                canActivate);
    }

    // ---------------- DTOs ----------------

    public record EventStatusView(
            boolean shiftLive,
            boolean customerOrderingOpen,
            EventView active,
            EventView next
    ) {}

    public record EventView(
            String id,
            String seriesId,
            String title,
            String location,
            Instant startAt,
            Instant endAt,
            boolean active,
            boolean fromSeries,
            boolean onlineOrderingOpen,
            boolean canActivate
    ) {}

    public record ErrorResponse(String code, String message) {}
}