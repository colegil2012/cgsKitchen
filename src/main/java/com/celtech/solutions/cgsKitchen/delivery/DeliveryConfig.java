package com.celtech.solutions.cgsKitchen.delivery;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Mock delivery provider — returns plausible quotes and dispatch IDs.
 * Wire up DoorDash Drive or Uber Direct by replacing the @Bean below.
 *
 * <p>See the food-service-suite docs/DELIVERY.md for full integration
 * details on each provider.
 */
@Slf4j
@Configuration
public class DeliveryConfig {

    @Bean
    public DeliveryProvider deliveryProvider(AppProperties props) {
        log.info("DeliveryProvider: {} (set app.delivery.provider to switch)",
                 props.delivery().provider());
        return new MockDeliveryProvider();
    }

    static class MockDeliveryProvider implements DeliveryProvider {
        @Override
        public String name() { return "mock"; }

        @Override
        public DeliveryQuote quote(QuoteRequest request) {
            log.info("[mock delivery quote] {} → {}",
                     request.pickupAddress(), request.dropoffAddress());
            return new DeliveryQuote(
                    "q_" + UUID.randomUUID(),
                    499,    // $4.99
                    25,     // 25 min ETA
                    true
            );
        }

        @Override
        public DeliveryDispatch dispatch(DispatchRequest request) {
            var id = "dlv_" + UUID.randomUUID();
            log.info("[mock delivery dispatch] order={} → {} (id={})",
                     request.orderId(), request.dropoffAddress(), id);
            return new DeliveryDispatch(
                    id,
                    "https://example.com/track/" + id,
                    "created"
            );
        }
    }
}
