package com.celtech.solutions.cgsKitchen.repositories.user;

import com.celtech.solutions.cgsKitchen.models.user.PaymentMethod;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Saved Stripe payment method mirrors.
 *
 * <p>The {@code findCardsByUser...} variant filters down to actual cards
 * (excluding Link, bank-account, etc.). This is the method to use from
 * customer-facing surfaces; the unfiltered variant returns every
 * mirrored payment method including ones we don't have meaningful
 * display data for. See {@code AccountController} for the rationale.
 */
public interface PaymentMethodRepository extends MongoRepository<PaymentMethod, String> {

    List<PaymentMethod> findByUserIdOrderByDefaultMethodDescUpdatedAtDesc(String userId);

    /**
     * Cards only, in default-first then most-recently-updated order.
     * Use this for the account page's saved-cards list.
     */
    List<PaymentMethod> findByUserIdAndTypeOrderByDefaultMethodDescUpdatedAtDesc(
            String userId, String type);

    Optional<PaymentMethod> findByStripePaymentMethodId(String stripePaymentMethodId);
}