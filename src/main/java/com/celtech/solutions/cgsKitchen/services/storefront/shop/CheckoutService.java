package com.celtech.solutions.cgsKitchen.services.storefront.shop;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.common.EmptyParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps Stripe payment operations.
 *
 * <p><b>Charge model — single-tenant, direct charges.</b> All Stripe calls
 * are performed with the account's secret key directly. There is no
 * connected-account routing, no {@code Stripe-Account} header, and no
 * {@code applicationFeeAmount}. Customer payments land in the account
 * associated with {@code STRIPE_SECRET_KEY}. If this ever needs to become
 * a multi-tenant platform (e.g. you onboard additional kitchens and want
 * to skim a per-transaction fee), the changes are:
 * <ul>
 *   <li>re-introduce {@code AppProperties.Stripe#connectedAccountId} and
 *       {@code platformFeeBps}</li>
 *   <li>switch from direct charges to destination charges using
 *       {@code PaymentIntentCreateParams.TransferData.setDestination(...)}</li>
 *   <li>store each tenant's {@code acct_…} id alongside their other
 *       configuration, not as a single global property</li>
 * </ul>
 *
 * <p>The Elements (eager-mount) flow uses
 * {@link #ensurePaymentIntent(Order, String, boolean)} on /checkout render
 * and {@link #updatePaymentIntent(Order, boolean)} when totals change.
 *
 * <p>The legacy redirect-to-Stripe-Checkout flow lives in
 * {@link #createCheckoutSession} and is kept for fallback.
 *
 * <p>Mock mode: if STRIPE_SECRET_KEY isn't configured, the legacy path
 * returns a fake URL. The Elements path throws — controller surfaces
 * a friendly error.
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final AppProperties props;
    private final OrderService orderService;
    private final UserService userService;
    private final PaymentMetrics metrics;

    @PostConstruct
    void initStripe() {
        if (props.stripe().isConfigured()) {
            Stripe.apiKey = props.stripe().secretKey();
            log.info("Stripe SDK initialized (live={})",
                    props.stripe().secretKey().startsWith("sk_live"));
        } else {
            log.warn("Stripe not configured — checkout will use mock URLs");
        }
    }

    // ================================================================
    //  Eager-mount Elements flow
    // ================================================================

    /**
     * Ensure a PaymentIntent exists for this order. If the order already
     * has one, fetch it; otherwise create a new one. Returns the
     * client_secret + paymentIntentId.
     *
     * <p>Called when /checkout renders. Safe to call multiple times for
     * the same order — the second call retrieves the existing intent
     * rather than creating a duplicate.
     */
    public PaymentIntentResult ensurePaymentIntent(
            Order order, String userEmail, boolean saveCard) throws StripeException {

        if (!props.stripe().isConfigured()) {
            throw new IllegalStateException("Stripe not configured");
        }

        // If the order already has an intent, retrieve and return it.
        if (order.getStripePaymentIntentId() != null) {
            PaymentIntent existing = PaymentIntent.retrieve(
                    order.getStripePaymentIntentId(), requestOptions(null));
            log.debug("Reused PaymentIntent {} for order {} (status={})",
                    existing.getId(), order.getId(), existing.getStatus());
            return new PaymentIntentResult(existing.getClientSecret(), existing.getId());
        }

        String customerId = null;
        if (userEmail != null) {
            User user = userService.findByEmail(userEmail).orElse(null);
            if (user != null) {
                customerId = ensureStripeCustomer(user);
            }
        }

        var params = PaymentIntentCreateParams.builder()
                .setCurrency("usd")
                .setAmount(order.getTotalCents())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                .putMetadata("order_id", order.getId())
                .putMetadata("fulfillment", order.getFulfillment().name())
                .setDescription("Order " + order.getId());

        if (customerId != null) {
            params.setCustomer(customerId);
            if (saveCard) {
                params.setSetupFutureUsage(
                        PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
            }
        }

        PaymentIntent intent = PaymentIntent.create(
                params.build(),
                requestOptions("pi_create_" + order.getId()));

        order.setStripePaymentIntentId(intent.getId());
        orderService.save(order);

        metrics.paymentCreated();
        log.info("Created PaymentIntent {} for order {} (amount={}, saveCard={})",
                intent.getId(), order.getId(), order.getTotalCents(), saveCard);

        log.info("Created PaymentIntent {} for order {} (amount={}, saveCard={})",
                intent.getId(), order.getId(), order.getTotalCents(), saveCard);

        return new PaymentIntentResult(intent.getClientSecret(), intent.getId());
    }

    /**
     * Push the order's current total (and saveCard flag) up to Stripe.
     * Called when fulfillment, delivery fee, or save-card toggle changes
     * on the checkout page after the Element has already mounted.
     *
     * <p>The same client_secret remains valid — the Element automatically
     * picks up the new amount at confirm time.
     */
    public void updatePaymentIntent(Order order, boolean saveCard) throws StripeException {
        if (order.getStripePaymentIntentId() == null) {
            log.warn("updatePaymentIntent called on order {} with no PaymentIntent",
                    order.getId());
            return;
        }

        var params = PaymentIntentUpdateParams.builder()
                .setAmount(order.getTotalCents())
                .putMetadata("fulfillment", order.getFulfillment().name());

        // setup_future_usage is mutable while the intent is in
        // requires_payment_method status. Set it if the user opted in,
        // explicitly clear it (EmptyParam) if they opted out — leaving
        // a previously-set value in place would silently save the card
        // against the user's wishes.
        if (saveCard && order.getUserId() != null) {
            params.setSetupFutureUsage(
                    PaymentIntentUpdateParams.SetupFutureUsage.OFF_SESSION);
        } else {
            params.setSetupFutureUsage(EmptyParam.EMPTY);
        }

        // PaymentIntent.update is an instance method in stripe-java 32.x —
        // retrieve first, then call update on the resource.
        PaymentIntent existing = PaymentIntent.retrieve(
                order.getStripePaymentIntentId(), requestOptions(null));
        PaymentIntent updated = existing.update(params.build(), requestOptions(null));

        metrics.paymentUpdated();
        log.debug("Updated PaymentIntent {} for order {} (newAmount={}, status={})",
                updated.getId(), order.getId(), updated.getAmount(), updated.getStatus());

        log.debug("Updated PaymentIntent {} for order {} (newAmount={}, status={})",
                updated.getId(), order.getId(), updated.getAmount(), updated.getStatus());
    }

    public record PaymentIntentResult(String clientSecret, String paymentIntentId) {}

    /**
     * Cancel a PaymentIntent on Stripe. Used by the abandoned-checkout
     * sweeper to clean up stale intents before their corresponding Order
     * row TTLs out of Mongo.
     *
     * <p>Idempotent at the Stripe level — cancelling an already-cancelled
     * or already-succeeded intent throws, which we swallow and log. Returns
     * the resulting status (or null if the call failed).
     */
    public String cancelPaymentIntent(String paymentIntentId) {
        if (!props.stripe().isConfigured() || paymentIntentId == null) {
            return null;
        }
        try {
            PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId, requestOptions(null));
            // Only cancel if it's in a cancellable state. PaymentIntents in
            // `succeeded`, `canceled`, or `processing` cannot be cancelled.
            String status = pi.getStatus();
            if (status == null
                    || "succeeded".equals(status)
                    || "canceled".equals(status)
                    || "processing".equals(status)) {
                log.debug("Skipping cancel for PI {} in non-cancellable status {}",
                        paymentIntentId, status);
                return status;
            }
            PaymentIntent cancelled = pi.cancel(requestOptions(null));
            metrics.paymentCancelled("sweeper");
            log.info("Cancelled stale PaymentIntent {} (was {})", paymentIntentId, status);
            return cancelled.getStatus();
        } catch (StripeException e) {
            log.warn("Could not cancel PaymentIntent {}: {}", paymentIntentId, e.getMessage());
            return null;
        }
    }

    // ================================================================
    //  Stripe Customer helpers
    // ================================================================

    public String ensureStripeCustomer(User user) throws StripeException {
        if (user.getStripeCustomerId() != null && !user.getStripeCustomerId().isBlank()) {
            return user.getStripeCustomerId();
        }
        Customer customer = Customer.create(
                CustomerCreateParams.builder()
                        .setEmail(user.getEmail())
                        .setName(user.getDisplayName())
                        .setPhone(user.getPhone())
                        .putMetadata("user_id", user.getId())
                        .build(),
                requestOptions("cust_" + user.getId()));
        userService.attachStripeCustomerId(user.getId(), customer.getId());
        log.info("Created Stripe Customer {} for user {}", customer.getId(), user.getId());
        return customer.getId();
    }

    private RequestOptions requestOptions(String idempotencyKey) {
        var b = RequestOptions.builder();
        if (idempotencyKey != null) {
            b.setIdempotencyKey(idempotencyKey);
        }
        return b.build();
    }
}