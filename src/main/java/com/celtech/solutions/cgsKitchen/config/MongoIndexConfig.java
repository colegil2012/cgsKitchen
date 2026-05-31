package com.celtech.solutions.cgsKitchen.config;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.DeliveryEvent;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.models.webhooks.WebhookEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.time.Duration;

/**
 * Creates TTL indexes programmatically. Three TTLs:
 * <ul>
 *   <li>{@code webhook_events.receivedAt} → {@link WebhookEvent#TTL_DAYS} days</li>
 *   <li>{@code orders.expiresAt} → per-document (set only for unfinished PENDING_PAYMENT)</li>
 *   <li>{@code delivery_events.occurredAt} → {@link DeliveryEvent#TTL_DAYS} days</li>
 * </ul>
 *
 * <p>Safe to re-run; Mongo's {@code ensureIndex} is idempotent.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MongoIndexConfig {

    private final MongoTemplate mongo;

    @PostConstruct
    public void initIndexes() {
        IndexOperations webhookOps = mongo.indexOps(WebhookEvent.class);
        webhookOps.createIndex(
                new Index().on("receivedAt", org.springframework.data.domain.Sort.Direction.ASC)
                        .expire(Duration.ofDays(WebhookEvent.TTL_DAYS))
                        .named("receivedAt_ttl")
        );

        IndexOperations orderOps = mongo.indexOps(Order.class);
        orderOps.createIndex(
                new Index().on("expiresAt", org.springframework.data.domain.Sort.Direction.ASC)
                        .expire(Duration.ZERO)
                        .named("expiresAt_ttl")
        );

        IndexOperations delivEvtOps = mongo.indexOps(DeliveryEvent.class);
        delivEvtOps.createIndex(
                new Index().on("occurredAt", org.springframework.data.domain.Sort.Direction.ASC)
                        .expire(Duration.ofDays(DeliveryEvent.TTL_DAYS))
                        .named("occurredAt_ttl")
        );

        log.info("Mongo TTL indexes ensured: webhook_events ({} days), orders (per-doc), delivery_events ({} days)",
                WebhookEvent.TTL_DAYS, DeliveryEvent.TTL_DAYS);
    }
}