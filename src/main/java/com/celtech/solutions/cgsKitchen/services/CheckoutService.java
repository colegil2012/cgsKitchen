package com.celtech.solutions.cgsKitchen.services;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.Order;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps Stripe Checkout. Returns a redirect URL the storefront sends the
 * customer to.
 *
 * <p>Mock mode: if STRIPE_SECRET_KEY isn't configured, returns a fake
 * URL pointing at the local order-confirm page. Lets you build / demo
 * the full flow without Stripe credentials.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final AppProperties props;
    private final OrderService orderService;

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

    /**
     * Create a Stripe Checkout Session for the given order. Returns the
     * URL to redirect the customer to.
     */
    public String createCheckoutSession(Order order, String siteBaseUrl) throws StripeException {
        if (!props.stripe().isConfigured()) {
            // Mock mode — pretend it worked and route to the confirm page
            String fakeSession = "cs_mock_" + order.getId();
            order.setStripeCheckoutSessionId(fakeSession);
            orderService.save(order);
            return siteBaseUrl + "/order/confirm?session_id=" + fakeSession + "&mock=true";
        }

        List<SessionCreateParams.LineItem> lineItems = new ArrayList<>();

        for (var item : order.getItems()) {
            lineItems.add(SessionCreateParams.LineItem.builder()
                    .setQuantity((long) item.getQuantity())
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("usd")
                            .setUnitAmount(item.getUnitPriceCents())
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(item.getName())
                                    .build())
                            .build())
                    .build());
        }

        if (order.getDeliveryFeeCents() > 0) {
            lineItems.add(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("usd")
                            .setUnitAmount(order.getDeliveryFeeCents())
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName("Delivery")
                                    .build())
                            .build())
                    .build());
        }

        var paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addAllLineItem(lineItems)
                .putMetadata("order_id", order.getId())
                .putMetadata("client_id", order.getClientId())
                .putMetadata("fulfillment", order.getFulfillment().name())
                .setSuccessUrl(siteBaseUrl + "/order/confirm?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(siteBaseUrl + "/checkout?cancelled=true");

        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            paramsBuilder.setCustomerEmail(order.getCustomerEmail());
        }

        if (order.getFulfillment() == Order.Fulfillment.DELIVERY) {
            paramsBuilder.setPhoneNumberCollection(
                    SessionCreateParams.PhoneNumberCollection.builder()
                            .setEnabled(true)
                            .build());
        }

        // Optional platform fee under Stripe Connect Standard
        int feeBps = props.stripe().platformFeeBps();
        if (feeBps > 0) {
            long fee = order.getTotalCents() * feeBps / 10_000;
            paramsBuilder.setPaymentIntentData(
                    SessionCreateParams.PaymentIntentData.builder()
                            .setApplicationFeeAmount(fee)
                            .build());
        }

        Session session = Session.create(paramsBuilder.build(), requestOptions());

        order.setStripeCheckoutSessionId(session.getId());
        orderService.save(order);

        return session.getUrl();
    }

    /**
     * Run all Stripe API calls on-behalf-of the connected account so funds
     * settle to the client's balance, not yours.
     */
    private RequestOptions requestOptions() {
        var b = RequestOptions.builder();
        var acct = props.stripe().connectedAccountId();
        if (acct != null && !acct.isBlank()) {
            b.setStripeAccount(acct);
        }
        return b.build();
    }
}
