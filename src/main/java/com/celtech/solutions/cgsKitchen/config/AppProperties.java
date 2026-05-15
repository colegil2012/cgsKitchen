package com.celtech.solutions.cgsKitchen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String clientId,
        String apiKey,
        List<String> corsAllowedOrigins,
        Storefront storefront,
        Stripe stripe,
        Delivery delivery
) {

    /**
     * Storefront-level config that drives the Thymeleaf templates
     * (brand name, hours, today's location, social links, etc).
     */
    public record Storefront(
            String brandName,
            String tagline,
            String addressToday,
            String hoursToday,
            String contactEmail,
            String contactPhone,
            String instagramUrl
    ) {}

    public record Stripe(
            String secretKey,
            String publishableKey,
            String webhookSecret,
            String connectedAccountId,
            String terminalLocationId,
            int platformFeeBps
    ) {
        public boolean isConfigured() {
            return secretKey != null && !secretKey.isBlank();
        }
    }

    public record Delivery(
            String provider,
            String pickupAddress,
            DoorDash doordash,
            Uber uber
    ) {
        public boolean isMock() { return "mock".equalsIgnoreCase(provider); }
        public boolean isDoorDash() { return "doordash".equalsIgnoreCase(provider); }
        public boolean isUber() { return "uber".equalsIgnoreCase(provider); }
    }

    public record DoorDash(
            String developerId,
            String keyId,
            String signingSecret
    ) {}

    public record Uber(
            String customerId,
            String clientId,
            String clientSecret
    ) {}
}
