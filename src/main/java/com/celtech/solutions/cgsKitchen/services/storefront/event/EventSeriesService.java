package com.celtech.solutions.cgsKitchen.services.storefront.event;

import com.celtech.solutions.cgsKitchen.models.storefront.event.EventSeries;
import com.celtech.solutions.cgsKitchen.repositories.storefront.event.EventSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * CRUD for {@link EventSeries} recurring templates.
 *
 * <p>Series have no activation lifecycle of their own — activating a
 * series materializes a concrete {@link com.celtech.solutions.cgsKitchen.models.storefront.event.Event}
 * via {@link EventService#activateSeriesOccurrence}. This service is just
 * the template editor.
 *
 * <p>Editing a series does NOT retroactively change already-materialized
 * occurrences — those snapshot their title/address/window at activation,
 * so historical and in-flight appearances are immune to later edits.
 * Only future materializations pick up the new values.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSeriesService {

    private final EventSeriesRepository repo;

    public Optional<EventSeries> findById(String id) {
        return repo.findById(id);
    }
    public List<EventSeries> findAll() {
        return repo.findAll();
    }
    public Page<EventSeries> findAll(Pageable pageable) {
        return repo.findAllByOrderByCreatedAtDesc(pageable);
    }

    public EventSeries create(EventSeries s) {
        EventSeries saved = repo.save(s);
        log.info("Created event series {} ({})", saved.getId(), saved.getTitle());
        return saved;
    }

    public EventSeries update(EventSeries s) {
        EventSeries existing = repo.findById(s.getId()).orElseThrow();
        s.setCreatedAt(existing.getCreatedAt());
        EventSeries saved = repo.save(s);
        log.info("Updated event series {} ({})", saved.getId(), saved.getTitle());
        return saved;
    }

    public void delete(String id) {
        repo.deleteById(id);
        log.info("Deleted event series {}", id);
    }
}