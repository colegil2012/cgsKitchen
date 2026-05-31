package com.celtech.solutions.cgsKitchen.delivery;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.delivery.uber.UberDirectClient;
import com.celtech.solutions.cgsKitchen.delivery.uber.UberDirectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Selects the {@link DeliveryProvider} implementation based on
 * {@code app.delivery.provider}:
 * <ul>
 *   <li>{@code uber}    — real Uber Direct (sandbox or prod, distinguished
 *                         by credentials)</li>
 *   <li>{@code mock}    — returns plausible fake quotes/dispatches; useful
 *                         when you want to demo without external calls</li>
 *   <li>any other value — falls back to mock with a warning</li>
 * </ul>
 *
 * <p>DoorDash will land here later as a third option.
 */
@Slf4j
@Configuration
public class DeliveryConfig {

    @Bean
    public DeliveryProvider deliveryProvider(AppProperties props,
                                             UberDirectClient uberClient) {
        AppProperties.Delivery cfg = props.delivery();
        String provider = cfg.provider() == null ? "mock" : cfg.provider().toLowerCase();

        switch (provider) {
            case "uber" -> {
                log.info("DeliveryProvider: uber (Uber Direct)");
                return new UberDirectProvider(uberClient, props);
            }
            case "mock" -> {
                log.info("DeliveryProvider: mock");
                return new MockDeliveryProvider();
            }
            default -> {
                log.warn("Unknown app.delivery.provider='{}' — falling back to mock", provider);
                return new MockDeliveryProvider();
            }
        }
    }

    /** Returns plausible quotes and dispatch IDs without any external calls. */
    static class MockDeliveryProvider implements DeliveryProvider {
        @Override
        public String name() { return "mock"; }

        @Override
        public DeliveryQuote quote(QuoteRequest request) {
            log.info("[mock quote] {} → {}", request.pickupAddress(), request.dropoffAddress());
            return new DeliveryQuote("q_" + UUID.randomUUID(), 499, 25, true);
        }

        @Override
        public DeliveryDispatch dispatch(DispatchRequest request) {
            var id = "dlv_" + UUID.randomUUID();
            log.info("[mock dispatch] order={} → {} (id={})",
                    request.orderId(), request.dropoffAddress(), id);
            return new DeliveryDispatch(id,
                    "https://example.com/track/" + id, "created");
        }
    }
}