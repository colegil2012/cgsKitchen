package com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.DeliveryEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DeliveryEventRepository extends MongoRepository<DeliveryEvent, String> {
    List<DeliveryEvent> findByOrderIdOrderByOccurredAtAsc(String orderId);
    List<DeliveryEvent> findByDeliveryExternalIdOrderByOccurredAtAsc(String deliveryExternalId);
}