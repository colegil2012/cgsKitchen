package com.celtech.solutions.cgsKitchen.controllers.api.pos;

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
 * POS-facing event status + activation.
 *
 * <p>The POS needs to know, at all times:
 * <ul>
 *   <li>Is an event currently live? (active AND now ∈ [start,end]) — this
 *       gates whether orders can be rung up, and supplies the eventId the
 *       POS stamps onto each order.</li>
 *   <li>If none is live, what's the next scheduled event and when does it
 *       start? — so the POS can show it with a time-gated Activate button.</li>
 * </ul>
 *
 * <p>All logic delegates to {@link EventService}; this controller only
 * shapes it for the POS and resolves the storefront timezone.
 *
 * <p><b>Activation gating</b> is enforced both here (server rejects an
 * early activate) and in the POS UI (button greyed until in-window). The
 * window: activation allowed once {@code now >= startAt - 15min}.
 */
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class PosEventController {

    /** How early before start the POS may activate an event. */
    private static final long ACTIVATION_LEAD_MINUTES = 15;

    private final EventService eventService;
    private final EventSummaryService eventSummaryService;
    private final AppProperties props;

    private ZoneId zone() {
        return ZoneId.of(props.storefront().timezone());
    }

    // ---------------- Read ----------------

    /**
     * Current POS event status: the live event (if any) and the next
     * scheduled event (if any), with the flags the UI needs to gate
     * ordering and activation.
     */
    @GetMapping("/status")
    public EventStatusView status() {
        Instant now = Instant.now();
        ZoneId zone = zone();

        Optional<Event> live = eventService.findCurrentlyOpen();
        Optional<Event> next = live.isPresent()
                ? Optional.empty()
                : eventService.findNextScheduled(zone);

        EventView liveView = live.map(e -> toView(e, now)).orElse(null);
        EventView nextView = next.map(e -> toView(e, now)).orElse(null);

        return new EventStatusView(live.isPresent(), liveView, nextView);
    }

    // ---------------- Activation ----------------

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable String id) {
        Instant now = Instant.now();
        ZoneId zone = zone();

        Event target = eventService.findById(id).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Event " + id + " not found"));
        }

        // Server-side gate mirroring the UI: must be within the lead window.
        // For recurring events startAt may be null until activation computes
        // it, so we gate on the computed/known start where available.
        Instant start = target.getStartAt();
        if (start != null) {
            Instant earliest = start.minusSeconds(ACTIVATION_LEAD_MINUTES * 60);
            if (now.isBefore(earliest)) {
                return ResponseEntity.status(400).body(new ErrorResponse(
                        "too_early",
                        "Event cannot be activated until within "
                                + ACTIVATION_LEAD_MINUTES + " minutes of start."));
            }
        }

        try {
            Event activated = eventService.activate(id, zone);
            return ResponseEntity.ok(toView(activated, now));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("activation_failed", e.getMessage()));
        }
    }

    /**
     * Per-event sales summary: income (orders + totals by payment method)
     * and items sold (rolled up, with modifier breakdown). Computed on the
     * backend from committed orders. Returns 404 if the event is unknown.
     */
    @GetMapping("/{id}/summary")
    public ResponseEntity<?> summary(@PathVariable String id) {
        if (eventService.findById(id).isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "Event " + id + " not found"));
        }
        return ResponseEntity.ok(eventSummaryService.summarize(id));
    }


    // ---------------- View mapping ----------------

    private EventView toView(Event e, Instant now) {
        boolean canActivate = false;
        if (!e.isActive() && e.getStartAt() != null) {
            Instant earliest = e.getStartAt().minusSeconds(ACTIVATION_LEAD_MINUTES * 60);
            canActivate = !now.isBefore(earliest);
        } else if (!e.isActive() && e.isRecurring() && e.getStartAt() == null) {
            // Recurring never-activated: activation will compute the slot.
            // Allow the server to decide; surface as activatable so the POS
            // can attempt it (server still gates).
            canActivate = true;
        }

        return new EventView(
                e.getId(),
                e.getTitle(),
                e.getAddress() == null ? null : e.getAddress().toSingleLine(),
                e.getStartAt(),
                e.getEndAt(),
                e.isActive(),
                e.isRecurring(),
                e.isRecurring() && e.getRecurrence() != null
                        ? e.getRecurrence().describe() : null,
                canActivate
        );
    }

    // ---------------- DTOs ----------------

    public record EventStatusView(
            boolean eventLive,
            EventView active,
            EventView next
    ) {}

    public record EventView(
            String id,
            String title,
            String location,
            Instant startAt,
            Instant endAt,
            boolean active,
            boolean recurring,
            String recurrenceLabel,
            boolean canActivate
    ) {}

    public record ErrorResponse(String code, String message) {}
}