package com.celtech.solutions.cgsKitchen.services.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.DeliveryEvent;
import com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen.DeliveryEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Append-only writer for the delivery-event telemetry stream.
 * Webhook controllers and the poller log every provider message here,
 * regardless of whether it changes the order's status.
 *
 * <p>Failures here NEVER block the primary mutation — this is observability,
 * not business logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryEventService {

    private final DeliveryEventRepository repo;

    public DeliveryEvent record(String orderId, String deliveryExternalId,
                                String provider, String providerStatus,
                                String kind, String detail) {
        return record(orderId, deliveryExternalId, provider, providerStatus,
                kind, detail, null, null);
    }

    public DeliveryEvent record(String orderId, String deliveryExternalId,
                                String provider, String providerStatus,
                                String kind, String detail,
                                Double lat, Double lng) {
        try {
            DeliveryEvent evt = DeliveryEvent.builder()
                    .orderId(orderId)
                    .deliveryExternalId(deliveryExternalId)
                    .provider(provider)
                    .providerStatus(providerStatus)
                    .kind(kind)
                    .detail(detail)
                    .lat(lat)
                    .lng(lng)
                    .occurredAt(Instant.now())
                    .build();
            return repo.save(evt);
        } catch (Exception e) {
            log.error("Failed to record delivery event for order {} (delivery {}, kind={})",
                    orderId, deliveryExternalId, kind, e);
            return null;
        }
    }

    public List<DeliveryEvent> historyForOrder(String orderId) {
        return repo.findByOrderIdOrderByOccurredAtAsc(orderId);
    }

    public List<DeliveryEvent> historyForDelivery(String deliveryExternalId) {
        return repo.findByDeliveryExternalIdOrderByOccurredAtAsc(deliveryExternalId);
    }
}