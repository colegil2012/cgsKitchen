package com.celtech.solutions.cgsKitchen.controllers.admin;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Admin CRUD for events, including recurring rules.
 *
 * <p>The save flow handles both one-time and recurring events from the
 * same form: a {@code recurring} flag toggles which set of fields are
 * meaningful. For one-time events, the operator picks specific start/end
 * datetimes. For recurring events, the operator picks a day-of-week and
 * time window; the per-activation start/end are computed at
 * activation time.
 */
@Slf4j
@Controller
@RequestMapping("/admin/events")
@RequiredArgsConstructor
public class AdminEventsController {

    private static final int PAGE_SIZE = 25;

    private final EventService eventService;
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

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<Event> events = eventService.findAll(PageRequest.of(page, PAGE_SIZE));
        model.addAttribute("events", events.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", events.getTotalPages());
        return "admin/events/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("event", Event.builder().build());
        return "admin/events/edit";
    }

    @GetMapping("/{id}")
    public String editForm(@PathVariable String id, Model model) {
        Event event = eventService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
        model.addAttribute("event", event);
        return "admin/events/edit";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute EventForm form, RedirectAttributes redirect) {
        try {
            Event toSave = buildEventFromForm(form);
            if (form.getId() != null && !form.getId().isBlank()) {
                toSave.setId(form.getId());
                eventService.update(toSave);
                redirect.addFlashAttribute("notice", "Event updated.");
            } else {
                eventService.create(toSave);
                redirect.addFlashAttribute("notice", "Event created.");
            }
        } catch (Exception ex) {
            log.warn("Event save failed", ex);
            redirect.addFlashAttribute("error", "Save failed: " + ex.getMessage());
            if (form.getId() != null && !form.getId().isBlank()) {
                return "redirect:/admin/events/" + form.getId();
            }
            return "redirect:/admin/events/new";
        }
        return "redirect:/admin/events";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable String id, RedirectAttributes redirect) {
        try {
            eventService.activate(id, zone());
            redirect.addFlashAttribute("notice", "Event activated.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", "Could not activate: " + ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable String id, RedirectAttributes redirect) {
        try {
            eventService.deactivate(id);
            redirect.addFlashAttribute("notice", "Event deactivated.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", "Could not deactivate: " + ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) {
        eventService.delete(id);
        redirect.addFlashAttribute("notice", "Event deleted.");
        return "redirect:/admin/events";
    }

    // ================================================================
    //  Form binding
    // ================================================================

    /**
     * Map the form data to an Event, including recurrence if checked.
     * Times are interpreted in the storefront's timezone.
     */
    private Event buildEventFromForm(EventForm form) {
        ZoneId zone = zone();

        Event.EventAddress address = Event.EventAddress.builder()
                .venueName(blank(form.getVenueName()) ? null : form.getVenueName().trim())
                .line1(form.getLine1())
                .line2(blank(form.getLine2()) ? null : form.getLine2().trim())
                .city(form.getCity())
                .state(form.getState())
                .postalCode(form.getPostalCode())
                .country(form.getCountry() == null ? "US" : form.getCountry())
                .build();

        Set<Event.Tag> tags = new HashSet<>();
        if (form.getTags() != null) {
            for (String t : form.getTags()) {
                try { tags.add(Event.Tag.valueOf(t)); } catch (IllegalArgumentException ignored) {}
            }
        }

        Event.EventBuilder builder = Event.builder()
                .title(form.getTitle())
                .description(blank(form.getDescription()) ? null : form.getDescription().trim())
                .internalNotes(blank(form.getInternalNotes()) ? null : form.getInternalNotes().trim())
                .address(address)
                .tags(tags);

        if (form.isRecurring()) {
            // Recurring event — build recurrence rule, leave startAt/endAt null
            // (they get stamped on activation).
            Event.RecurrenceRule rule = Event.RecurrenceRule.builder()
                    .dayOfWeek(DayOfWeek.valueOf(form.getDayOfWeek()))
                    .startTime(LocalTime.parse(form.getRecurrenceStartTime()))
                    .endTime(LocalTime.parse(form.getRecurrenceEndTime()))
                    .effectiveFrom(parseLocalDateAsInstant(form.getEffectiveFrom(), zone))
                    .effectiveUntil(parseLocalDateAsInstant(form.getEffectiveUntil(), zone))
                    .build();
            builder.recurrence(rule);
            // Tag this as RECURRING for filtering convenience
            tags.add(Event.Tag.RECURRING);
        } else {
            // One-time event — parse start/end as local datetimes
            LocalDateTime startLdt = LocalDateTime.parse(form.getStartAt());
            LocalDateTime endLdt = LocalDateTime.parse(form.getEndAt());
            builder.startAt(startLdt.atZone(zone).toInstant());
            builder.endAt(endLdt.atZone(zone).toInstant());
            builder.recurrence(null);
        }

        return builder.build();
    }

    private static Instant parseLocalDateAsInstant(String dateStr, ZoneId zone) {
        if (blank(dateStr)) return null;
        return LocalDate.parse(dateStr).atStartOfDay(zone).toInstant();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ================================================================
    //  Form DTO
    // ================================================================

    /**
     * Combined form for both one-time and recurring events. The
     * {@code recurring} flag controls which set of fields is meaningful.
     * Validation is light — invalid input throws at parse time, the
     * save method catches and surfaces as a flash error.
     */
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

        // One-time event fields
        private String startAt;        // "yyyy-MM-dd'T'HH:mm"
        private String endAt;

        // Recurring fields
        private boolean recurring;
        private String dayOfWeek;             // MONDAY..SUNDAY
        private String recurrenceStartTime;   // "HH:mm"
        private String recurrenceEndTime;     // "HH:mm"
        private String effectiveFrom;         // "yyyy-MM-dd" (optional)
        private String effectiveUntil;        // "yyyy-MM-dd" (optional)
    }
}