package com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.OrderEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderEventRepository extends MongoRepository<OrderEvent, String> {
    List<OrderEvent> findByOrderIdOrderByOccurredAtAsc(String orderId);
    List<OrderEvent> findByOrderIdOrderByOccurredAtDesc(String orderId);
}