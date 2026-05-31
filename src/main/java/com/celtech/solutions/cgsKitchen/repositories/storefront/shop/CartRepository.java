package com.celtech.solutions.cgsKitchen.repositories.storefront.shop;

import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Cart lookups. Unique sparse indexes on {@code userId} and
 * {@code cookieId} mean each query returns at most one document.
 *
 * <p>Renamed from {@code PersistentCartRepository} when the cart layer
 * was unified — the old session-scoped {@code Cart} bean is gone, so
 * the "Persistent" qualifier is no longer needed for disambiguation.
 */
public interface CartRepository extends MongoRepository<Cart, String> {

    Optional<Cart> findByUserId(String userId);
    Optional<Cart> findByCookieId(String cookieId);
    Optional<Cart> findByActiveOrderId(String activeOrderId);
}