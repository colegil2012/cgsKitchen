package com.celtech.solutions.cgsKitchen.repositories;

import com.celtech.solutions.cgsKitchen.models.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByStripeCheckoutSessionId(String sessionId);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);

    Page<Order> findByClientId(String clientId, Pageable pageable);
    List<Order> findByClientIdAndStatus(String clientId, Order.Status status);
}
