package com.celtech.solutions.cgsKitchen.models.storefront.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A recurring schedule template — e.g. "Tue 5-10pm and Sat 11am-3pm at
 * Mile Wide", or "Mon-Thu 11-2 at the office park".
 *
 * <p>An {@code EventSeries} is never itself "live." It is the definition
 * from which concrete {@link Event} occurrences are materialized at
 * activation time (Philosophy 1 — on-demand materialization, not
 * pre-generation). When the operator activates a series for a given day,
 * {@code EventService} finds the {@link RecurrenceRule.DayWindow} matching
 * that day, creates a concrete {@link Event} snapshotting the series'
 * title/address/that-window's-times, and seeds the occurrence's
 * {@code onlineOrderingOpen} from {@link #defaultOnlineOrderingOpen}.
 *
 * <p><b>Per-day windows:</b> the rule holds a list of {@code DayWindow}s,
 * each carrying its own day-of-week and start/end time. "Mon-Thu 11-2" is
 * four windows with identical times; "Tue 5-10, Sat 11-3" is two windows
 * with different times. This subsumes the old single-day-single-time model.
 *
 * <p><b>On/off:</b> a series is "in service" based on its
 * {@link RecurrenceRule#getEffectiveFrom()}/{@code effectiveUntil} window.
 *
 * <p>One-time events are NOT series — they're created directly as
 * {@link Event}s with {@code seriesId == null}.
 */
@Document(collection = "event_series")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSeries {

    @Id
    private String id;

    /** Human-readable title — copied onto each materialized occurrence. */
    private String title;

    /** Free-form description for customer-facing display. */
    private String description;

    /** Internal notes for the operator — not customer-facing. */
    private String internalNotes;

    /** Venue address. Snapshotted onto each occurrence at activation. */
    private Event.EventAddress address;

    /** The weekly pattern (per-day windows). Required to be meaningful. */
    private RecurrenceRule recurrence;

    /** Categorization tags — copied onto materialized occurrences. */
    private Set<Event.Tag> tags;

    /**
     * Seeds {@link Event#isOnlineOrderingOpen()} on each occurrence
     * materialized from this series. Defaults true. Set false for a
     * recurring walk-in-only series (e.g. a standing private/catering gig
     * that never takes online orders). The operator can still toggle an
     * individual occurrence live.
     */
    @Builder.Default
    private boolean defaultOnlineOrderingOpen = true;

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    /**
     * True if this series is in effect at the given instant — i.e. its
     * effective window (if any) contains {@code now}.
     */
    public boolean isInService(Instant now) {
        if (recurrence == null) return false;
        Instant from = recurrence.getEffectiveFrom();
        Instant until = recurrence.getEffectiveUntil();
        if (from != null && now.isBefore(from)) return false;
        if (until != null && now.isAfter(until)) return false;
        return true;
    }

    /**
     * Weekly recurrence rule with per-day time windows.
     *
     * <p>Times are interpreted in the storefront's configured timezone
     * (see {@code app.storefront.timezone}). The rule is timezone-naive:
     * "Tuesday 5pm" means 5pm local time regardless of DST.
     * {@code effectiveFrom}/{@code effectiveUntil} are absolute UTC
     * instants — "when does the schedule start/end."
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecurrenceRule {

        /** Per-day windows. Each day-of-week appears at most once. */
        @Builder.Default
        private List<DayWindow> windows = new ArrayList<>();

        /**
         * First day this schedule is in effect (UTC instant, typically
         * midnight in storefront timezone). Null = effective immediately.
         */
        private Instant effectiveFrom;

        /**
         * Last day this schedule is in effect (UTC instant). Null = forever.
         */
        private Instant effectiveUntil;

        /** The window for a given day-of-week, if the rule has one. */
        public DayWindow windowFor(DayOfWeek day) {
            if (windows == null) return null;
            for (DayWindow w : windows) {
                if (w.getDayOfWeek() == day) return w;
            }
            return null;
        }

        /**
         * Human-readable summary. Lists each window in day order:
         * "Tue 5:00 PM – 10:00 PM, Sat 11:00 AM – 3:00 PM". When every
         * window shares the same times and the days are contiguous, it
         * collapses to a range: "Mon–Thu 11 AM – 2 PM".
         */
        public String describe() {
            if (windows == null || windows.isEmpty()) return "Recurring";

            List<DayWindow> sorted = new ArrayList<>(windows);
            sorted.sort(Comparator.comparingInt(w -> w.getDayOfWeek().getValue()));

            // Collapse: all same times + contiguous days → "Mon–Thu 11 AM – 2 PM"
            if (sorted.size() > 1 && allSameTimes(sorted) && contiguous(sorted)) {
                DayWindow first = sorted.get(0);
                DayWindow last = sorted.get(sorted.size() - 1);
                return shortDay(first.getDayOfWeek()) + "–" + shortDay(last.getDayOfWeek())
                        + " " + fmt(first.getStartTime()) + " – " + fmt(first.getEndTime());
            }

            // Otherwise list each window.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                DayWindow w = sorted.get(i);
                if (i > 0) sb.append(", ");
                sb.append(shortDay(w.getDayOfWeek())).append(' ')
                        .append(fmt(w.getStartTime())).append(" – ").append(fmt(w.getEndTime()));
            }
            return sb.toString();
        }

        private static boolean allSameTimes(List<DayWindow> ws) {
            LocalTime s = ws.get(0).getStartTime(), e = ws.get(0).getEndTime();
            for (DayWindow w : ws) {
                if (s == null || e == null) return false;
                if (!s.equals(w.getStartTime()) || !e.equals(w.getEndTime())) return false;
            }
            return true;
        }

        private static boolean contiguous(List<DayWindow> ws) {
            for (int i = 1; i < ws.size(); i++) {
                if (ws.get(i).getDayOfWeek().getValue()
                        != ws.get(i - 1).getDayOfWeek().getValue() + 1) {
                    return false;
                }
            }
            return true;
        }

        private static String shortDay(DayOfWeek d) {
            String s = d.toString();
            return s.charAt(0) + s.substring(1, 3).toLowerCase();
        }

        private static String fmt(LocalTime t) {
            if (t == null) return "?";
            int hour = t.getHour();
            int min = t.getMinute();
            String ampm = hour >= 12 ? "PM" : "AM";
            int displayHour = hour % 12;
            if (displayHour == 0) displayHour = 12;
            return min == 0
                    ? displayHour + " " + ampm
                    : String.format("%d:%02d %s", displayHour, min, ampm);
        }
    }

    /** A single day's time window within a {@link RecurrenceRule}. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayWindow {
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
    }
}