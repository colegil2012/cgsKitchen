package com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByStripeCheckoutSessionId(String sessionId);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);
    Optional<Order> findByStripeChargeId(String chargeId);

    Optional<Order> findByDeliveryExternalId(String deliveryExternalId);
    List<Order> findByStatusAndDeliveryProvider(Order.Status status, String deliveryProvider);

    List<Order> findByStatus(Order.Status status);
    List<Order> findByStatusInOrderByCreatedAtAsc(Collection<Order.Status> statuses);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<Order> findByStatusOrderByCreatedAtDesc(Order.Status status, Pageable pageable);
    Page<Order> findByStatusNotOrderByCreatedAtDesc(Order.Status status, Pageable pageable);

    List<Order> findByStatusAndCreatedAtBefore(Order.Status status, java.time.Instant cutoff);

    List<Order> findByUserIdOrderByCreatedAtDesc(String userId);
    long countByUserId(String userId);
}
