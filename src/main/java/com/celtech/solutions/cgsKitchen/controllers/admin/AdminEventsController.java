package com.celtech.solutions.cgsKitchen.controllers.admin;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import com.celtech.solutions.cgsKitchen.models.storefront.event.EventSeries;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventSeriesService;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin events page (single page, three regions) + per-event stats.
 *
 * <p><b>Page layout</b> ({@code /admin/events}):
 * <ul>
 *   <li>Active-shift banner — the currently operator-open occurrence, if
 *       any, with a Close-shift action.</li>
 *   <li>Recurring series region — {@link EventSeries} templates; activate
 *       materializes an occurrence.</li>
 *   <li>One-time events region — current/upcoming one-time {@link Event}s.</li>
 *   <li>Past events region — ended, inactive events (one-time AND
 *       materialized occurrences), newest first, paginated. Each links to
 *       its stats summary.</li>
 * </ul>
 *
 * <p><b>Create/edit</b> uses one form with a {@code recurring} toggle:
 * recurring → {@link EventSeries}; one-time → {@link Event}.
 *
 * <p><b>Activation</b> is split by type:
 * {@code POST /admin/events/{id}/activate} (one-time) vs
 * {@code POST /admin/series/{id}/activate} (series → materialize). Both
 * surface the single-active "close current shift first" rejection as a
 * flash error. Deactivation ("close shift") always targets the active
 * concrete event.
 */
@Slf4j
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminEventsController {

    private static final int PAST_PAGE_SIZE = 25;

    private final EventService eventService;
    private final EventSeriesService seriesService;
    private final EventSummaryService summaryService;
    private final AppProperties props;

    @ModelAttribute("brand")
    public AppProperties.Storefront brand() {
        return props.storefront();
    }

    @ModelAttribute("zoneId")
    public ZoneId zone() {
        String tz = props.storefront().timezone();
        return ZoneId.of(tz == null || tz.isBlank() ? "America/New_York" : tz);
    }

    @ModelAttribute("allTags")
    public Event.Tag[] allTags() {
        return Event.Tag.values();
    }

    @ModelAttribute("allDaysOfWeek")
    public DayOfWeek[] allDaysOfWeek() {
        return DayOfWeek.values();
    }

    // ================================================================
    //  List page (three regions + active-shift banner)
    // ================================================================

    @GetMapping("/events")
    public String list(@RequestParam(defaultValue = "0") int pastPage, Model model) {
        Instant now = Instant.now();

        // Active shift (operator-open) — banner + section highlight.
        Event activeShift = eventService.findActiveShift().orElse(null);
        model.addAttribute("activeShift", activeShift);

        // Recurring series templates.
        model.addAttribute("series", seriesService.findAll());

        // One-time events, current/upcoming.
        List<Event> oneTime = eventService.findOneTimeUpcoming(now);
        model.addAttribute("oneTimeEvents", oneTime);

        // Past events — ended + inactive, paginated newest-first.
        Page<Event> past = eventService.findPastEvents(
                now, PageRequest.of(pastPage, PAST_PAGE_SIZE));
        model.addAttribute("pastEvents", past.getContent());
        model.addAttribute("pastPage", pastPage);
        model.addAttribute("pastTotalPages", past.getTotalPages());

        return "admin/events/list";
    }

    // ================================================================
    //  Create / edit (shared toggle form)
    // ================================================================

    @GetMapping("/events/new")
    public String newForm(Model model) {
        model.addAttribute("form", new EventForm());
        model.addAttribute("editing", false);
        return "admin/events/edit";
    }

    /** Edit a one-time event. */
    /** Edit a one-time event. {id} constrained to a Mongo ObjectId so it
     *  cannot swallow the literal /events/new route. */
    @GetMapping("/events/{id:[a-fA-F0-9]{24}}")
    public String editEvent(@PathVariable String id, Model model) {
        Event e = eventService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
        model.addAttribute("form", EventForm.fromEvent(e, zone()));
        model.addAttribute("editing", true);
        return "admin/events/edit";
    }

    /** Edit a recurring series. */
    @GetMapping("/series/{id}")
    public String editSeries(@PathVariable String id, Model model) {
        EventSeries s = seriesService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Series not found: " + id));
        model.addAttribute("form", EventForm.fromSeries(s, zone()));
        model.addAttribute("editing", true);
        return "admin/events/edit";
    }

    @PostMapping("/events/save")
    public String save(@ModelAttribute EventForm form, RedirectAttributes redirect) {
        try {
            if (form.isRecurring()) {
                EventSeries s = buildSeriesFromForm(form);
                if (notBlank(form.getId())) {
                    s.setId(form.getId());
                    seriesService.update(s);
                    redirect.addFlashAttribute("notice", "Series updated.");
                } else {
                    seriesService.create(s);
                    redirect.addFlashAttribute("notice", "Series created.");
                }
            } else {
                Event e = buildOneTimeFromForm(form);
                if (notBlank(form.getId())) {
                    e.setId(form.getId());
                    eventService.updateOneTime(e);
                    redirect.addFlashAttribute("notice", "Event updated.");
                } else {
                    eventService.createOneTime(e);
                    redirect.addFlashAttribute("notice", "Event created.");
                }
            }
        } catch (Exception ex) {
            log.warn("Event/series save failed", ex);
            redirect.addFlashAttribute("error", "Save failed: " + ex.getMessage());
            if (notBlank(form.getId())) {
                return "redirect:/admin/" + (form.isRecurring() ? "series/" : "events/") + form.getId();
            }
            return "redirect:/admin/events/new";
        }
        return "redirect:/admin/events";
    }

    // ================================================================
    //  Activation — split by type; both surface reject-while-open
    // ================================================================

    @PostMapping("/events/{id}/activate")
    public String activateOneTime(@PathVariable String id, RedirectAttributes redirect) {
        try {
            eventService.activateOneTimeEvent(id);
            redirect.addFlashAttribute("notice", "Event activated — shift open.");
        } catch (EventService.ShiftOpenException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", "Could not activate: " + ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    @PostMapping("/series/{id}/activate")
    public String activateSeries(@PathVariable String id, RedirectAttributes redirect) {
        try {
            Event occ = eventService.activateSeriesOccurrence(id, zone());
            redirect.addFlashAttribute("notice",
                    "Series activated — shift open for " + occ.getOccurrenceDate() + ".");
        } catch (EventService.ShiftOpenException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", "Could not activate: " + ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    /** Close the open shift (explicit operator action). Targets a concrete Event. */
    @PostMapping("/events/{id}/deactivate")
    public String closeShift(@PathVariable String id, RedirectAttributes redirect) {
        try {
            eventService.deactivate(id);
            redirect.addFlashAttribute("notice", "Shift closed.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", "Could not close shift: " + ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    /**
     * Toggle online ordering for a concrete event (the active shift banner
     * uses this). Explicit set via the {@code enabled} form param.
     */
    @PostMapping("/events/{id}/online-ordering")
    public String setOnlineOrdering(@PathVariable String id,
                                    @RequestParam boolean enabled,
                                    RedirectAttributes redirect) {
        try {
            eventService.setOnlineOrdering(id, enabled);
            redirect.addFlashAttribute("notice",
                    "Online ordering " + (enabled ? "enabled." : "disabled — POS still active."));
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", "Could not update online ordering: " + ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    // ================================================================
    //  Delete
    // ================================================================

    @PostMapping("/events/{id}/delete")
    public String deleteEvent(@PathVariable String id, RedirectAttributes redirect) {
        eventService.deleteEvent(id);
        redirect.addFlashAttribute("notice", "Event deleted.");
        return "redirect:/admin/events";
    }

    @PostMapping("/series/{id}/delete")
    public String deleteSeries(@PathVariable String id, RedirectAttributes redirect) {
        seriesService.delete(id);
        redirect.addFlashAttribute("notice", "Series deleted.");
        return "redirect:/admin/events";
    }

    // ================================================================
    //  Stats drill-in (past event → summary)
    // ================================================================

    @GetMapping("/events/{id}/summary")
    public String summary(@PathVariable String id, Model model) {
        Event e = eventService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
        model.addAttribute("event", e);
        model.addAttribute("summary", summaryService.summarize(id));
        return "admin/events/summary";
    }

    // ================================================================
    //  Form binding
    // ================================================================

    private EventSeries buildSeriesFromForm(EventForm form) {
        ZoneId zone = zone();
        Set<Event.Tag> tags = parseTags(form.getTags());
        tags.add(Event.Tag.RECURRING);

        List<EventSeries.DayWindow> windows = form.buildDayWindows();
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("Select at least one day with start and end times.");
        }

        EventSeries.RecurrenceRule rule = EventSeries.RecurrenceRule.builder()
                .windows(windows)
                .effectiveFrom(parseLocalDateAsInstant(form.getEffectiveFrom(), zone))
                .effectiveUntil(parseLocalDateAsInstant(form.getEffectiveUntil(), zone))
                .build();

        return EventSeries.builder()
                .title(form.getTitle())
                .description(blank(form.getDescription()) ? null : form.getDescription().trim())
                .internalNotes(blank(form.getInternalNotes()) ? null : form.getInternalNotes().trim())
                .address(buildAddress(form))
                .recurrence(rule)
                .tags(tags)
                .defaultOnlineOrderingOpen(form.isOnlineOrderingOpen())
                .build();
    }

    private Event buildOneTimeFromForm(EventForm form) {
        ZoneId zone = zone();
        Set<Event.Tag> tags = parseTags(form.getTags());
        tags.add(Event.Tag.ONE_TIME);

        LocalDateTime startLdt = LocalDateTime.parse(form.getStartAt());
        LocalDateTime endLdt = LocalDateTime.parse(form.getEndAt());

        return Event.builder()
                .title(form.getTitle())
                .description(blank(form.getDescription()) ? null : form.getDescription().trim())
                .internalNotes(blank(form.getInternalNotes()) ? null : form.getInternalNotes().trim())
                .address(buildAddress(form))
                .tags(tags)
                .startAt(startLdt.atZone(zone).toInstant())
                .endAt(endLdt.atZone(zone).toInstant())
                .onlineOrderingOpen(form.isOnlineOrderingOpen())
                .build();
    }

    private Event.EventAddress buildAddress(EventForm form) {
        return Event.EventAddress.builder()
                .venueName(blank(form.getVenueName()) ? null : form.getVenueName().trim())
                .line1(form.getLine1())
                .line2(blank(form.getLine2()) ? null : form.getLine2().trim())
                .city(form.getCity())
                .state(form.getState())
                .postalCode(form.getPostalCode())
                .country(form.getCountry() == null ? "US" : form.getCountry())
                .build();
    }

    private static Set<Event.Tag> parseTags(String[] raw) {
        Set<Event.Tag> tags = new HashSet<>();
        if (raw != null) {
            for (String t : raw) {
                try { tags.add(Event.Tag.valueOf(t)); } catch (IllegalArgumentException ignored) {}
            }
        }
        return tags;
    }

    private static Instant parseLocalDateAsInstant(String dateStr, ZoneId zone) {
        if (blank(dateStr)) return null;
        return LocalDate.parse(dateStr).atStartOfDay(zone).toInstant();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ================================================================
    //  Form DTO
    // ================================================================

    @lombok.Data
    public static class EventForm {
        private String id;

        private String title;
        private String description;
        private String internalNotes;

        // Address
        private String venueName;
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String postalCode;
        private String country;

        // Tags
        private String[] tags;

        // Online ordering — defaults true (most events). False = private /
        // walk-in-only at creation; for a series this seeds each occurrence.
        private boolean onlineOrderingOpen = true;

        // One-time event fields
        private String startAt;        // "yyyy-MM-dd'T'HH:mm"
        private String endAt;

        // Recurring fields
        private boolean recurring;
        private String effectiveFrom;         // "yyyy-MM-dd" (optional)
        private String effectiveUntil;        // "yyyy-MM-dd" (optional)

        /**
         * Per-day windows, keyed by DayOfWeek name. The form posts, for each
         * day: {@code dayEnabled[MONDAY]=on}, {@code dayStart[MONDAY]=11:00},
         * {@code dayEnd[MONDAY]=14:00}. Spring binds the indexed maps from
         * inputs named {@code dayEnabled[MONDAY]} etc. A day contributes a
         * window only when enabled AND both times are present.
         */
        private Map<String, Boolean> dayEnabled = new HashMap<>();
        private Map<String, String> dayStart = new HashMap<>();
        private Map<String, String> dayEnd = new HashMap<>();

        /** Build the DayWindow list from the per-day maps (enabled + both times). */
        List<EventSeries.DayWindow> buildDayWindows() {
            List<EventSeries.DayWindow> out = new ArrayList<>();
            for (DayOfWeek d : DayOfWeek.values()) {
                String key = d.name();
                boolean on = Boolean.TRUE.equals(dayEnabled.get(key));
                String s = dayStart.get(key);
                String e = dayEnd.get(key);
                boolean sOk = s != null && !s.isBlank();
                boolean eOk = e != null && !e.isBlank();
                if (on && sOk && eOk) {
                    out.add(EventSeries.DayWindow.builder()
                            .dayOfWeek(d)
                            .startTime(LocalTime.parse(s))
                            .endTime(LocalTime.parse(e))
                            .build());
                }
            }
            return out;
        }

        /** Prefill the form from a one-time event for editing. */
        static EventForm fromEvent(Event e, ZoneId zone) {
            EventForm f = new EventForm();
            f.id = e.getId();
            f.title = e.getTitle();
            f.description = e.getDescription();
            f.internalNotes = e.getInternalNotes();
            applyAddress(f, e.getAddress());
            f.tags = e.getTags() == null ? null
                    : e.getTags().stream().map(Enum::name).toArray(String[]::new);
            f.recurring = false;
            f.onlineOrderingOpen = e.isOnlineOrderingOpen();
            if (e.getStartAt() != null) {
                f.startAt = LocalDateTime.ofInstant(e.getStartAt(), zone).toString();
            }
            if (e.getEndAt() != null) {
                f.endAt = LocalDateTime.ofInstant(e.getEndAt(), zone).toString();
            }
            return f;
        }

        /** Prefill the form from a series for editing. */
        static EventForm fromSeries(EventSeries s, ZoneId zone) {
            EventForm f = new EventForm();
            f.id = s.getId();
            f.title = s.getTitle();
            f.description = s.getDescription();
            f.internalNotes = s.getInternalNotes();
            applyAddress(f, s.getAddress());
            f.tags = s.getTags() == null ? null
                    : s.getTags().stream().map(Enum::name).toArray(String[]::new);
            f.recurring = true;
            f.onlineOrderingOpen = s.isDefaultOnlineOrderingOpen();
            EventSeries.RecurrenceRule r = s.getRecurrence();
            if (r != null) {
                if (r.getWindows() != null) {
                    for (EventSeries.DayWindow w : r.getWindows()) {
                        if (w.getDayOfWeek() == null) continue;
                        String key = w.getDayOfWeek().name();
                        f.dayEnabled.put(key, true);
                        if (w.getStartTime() != null) f.dayStart.put(key, w.getStartTime().toString());
                        if (w.getEndTime() != null) f.dayEnd.put(key, w.getEndTime().toString());
                    }
                }
                if (r.getEffectiveFrom() != null) {
                    f.effectiveFrom = LocalDate.ofInstant(r.getEffectiveFrom(), zone).toString();
                }
                if (r.getEffectiveUntil() != null) {
                    f.effectiveUntil = LocalDate.ofInstant(r.getEffectiveUntil(), zone).toString();
                }
            }
            return f;
        }

        private static void applyAddress(EventForm f, Event.EventAddress a) {
            if (a == null) return;
            f.venueName = a.getVenueName();
            f.line1 = a.getLine1();
            f.line2 = a.getLine2();
            f.city = a.getCity();
            f.state = a.getState();
            f.postalCode = a.getPostalCode();
            f.country = a.getCountry();
        }
    }
}