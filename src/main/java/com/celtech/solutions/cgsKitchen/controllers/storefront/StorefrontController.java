package com.celtech.solutions.cgsKitchen.controllers.storefront;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.event.EventOccurrence;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.menu.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Controller
@RequiredArgsConstructor
public class StorefrontController {

    private static final int HORIZON_DAYS = 60;

    private final MenuService menuService;
    private final EventService eventService;
    private final AppProperties props;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("featured", menuService.findAvailable().stream()
                .filter(m -> m.getBadgeLabel() != null)
                .limit(3)
                .toList());
        return "storefront/home";
    }

    @GetMapping("/menu")
    public String menu(Model model) {
        model.addAttribute("menuByCategory", menuService.findGroupedByCategory());
        return "storefront/menu";
    }

    @GetMapping("/about")
    public String about() {
        return "storefront/about";
    }

    @GetMapping("/contact")
    public String contact() {
        return "storefront/contact";
    }

    @GetMapping("/events")
    public String calendar(Model model) {
        ZoneId zone = zone();
        LocalDate today = LocalDate.now(zone);
        LocalDate horizon = today.plusDays(HORIZON_DAYS);

        List<EventOccurrence> occurrences = eventService.expandOccurrences(today, horizon, zone);

        // Group by week (using Monday-start week-of-year)
        Map<LocalDate, List<EventOccurrence>> byWeek = new TreeMap<>();
        for (EventOccurrence occ : occurrences) {
            LocalDate weekStart = occ.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            byWeek.computeIfAbsent(weekStart, k -> new java.util.ArrayList<>()).add(occ);
        }

        // Re-order into a LinkedHashMap so Thymeleaf iterates in week order
        Map<LocalDate, List<EventOccurrence>> sorted = new LinkedHashMap<>(byWeek);

        model.addAttribute("weeksOfOccurrences", sorted);
        model.addAttribute("zoneId", zone);
        model.addAttribute("today", today);
        model.addAttribute("horizonDays", HORIZON_DAYS);
        return "storefront/events";
    }

    private ZoneId zone() {
        String tz = props.storefront().timezone();
        return ZoneId.of(tz == null || tz.isBlank() ? "America/New_York" : tz);
    }

}
