package com.celtech.solutions.cgsKitchen.repositories.storefront.event;

import com.celtech.solutions.cgsKitchen.models.storefront.event.EventSeries;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface EventSeriesRepository extends MongoRepository<EventSeries, String> {

    /** Admin listing — paginated, newest first. */
    Page<EventSeries> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** All series, for calendar projection + "next event" computation. */
    List<EventSeries> findAll();
}