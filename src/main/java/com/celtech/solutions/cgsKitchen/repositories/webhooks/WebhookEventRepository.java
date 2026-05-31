package com.celtech.solutions.cgsKitchen.repositories.webhooks;

import com.celtech.solutions.cgsKitchen.models.webhooks.WebhookEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface WebhookEventRepository extends MongoRepository<WebhookEvent, String> {
    Optional<WebhookEvent> findByProviderAndEventId(String provider, String eventId);
    Page<WebhookEvent> findByProviderOrderByReceivedAtDesc(String provider, Pageable pageable);
}