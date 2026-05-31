package com.celtech.solutions.cgsKitchen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String clientId,
        String apiKey,
        List<String> corsAllowedOrigins,
        Security security,
        Cookies cookies,
        Storefront storefront,
        Stripe stripe,
        Delivery delivery,
        Captcha captcha,
        Kitchen kitchen,
        Checkout checkout
) {

    public record Security(
            Bcrypt bcrypt
    ) {
        public record Bcrypt(int strength) {}
    }

    public record Cookies(
            boolean secure
    ) {}

    /**
     * Storefront-level config that drives the Thymeleaf templates
     * (brand name, hours, today's location, social links, etc).
     */
    public record Storefront(
            String brandName,
            String tagline,
            String contactEmail,
            String contactPhone,
            String instagramUrl,
            String facebookUrl,
            String timezone,
            Images images
    ) {}

    public record Captcha(
            String siteKey,
            String secretKey
    ) {
        public boolean isConfigured() {
            return siteKey != null && !siteKey.isBlank()
                    && secretKey != null && !secretKey.isBlank();
        }
    }

    public record Stripe(
            String secretKey,
            String publishableKey,
            String webhookSecret,
            String terminalLocationId
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
            String clientSecret,
            String webhookSigningKey
    ) {}

    /**
     * Kitchen throughput configuration. Capacity is the number of
     * concurrent "slots" the kitchen can work on — roughly one slot per
     * pair of hands. A solo food truck cook is typically 2; a fully-
     * staffed kitchen 4-6. Used by KitchenQuoteService to compute the
     * "next order will be ready in X min" estimate.
     *
     * <p>{@code defaultItemPrepMinutes} is the fallback when a menu item
     * lacks an explicit time AND its category lacks a default — e.g. a
     * newly-created item with no metadata.
     */
    public record Kitchen(
            int capacity,
            int defaultItemPrepMinutes,
            int perItemSurchargeMinutes
    ) {}

    public record Checkout(boolean debugJs) {}

    public record Images(
            String baseUrl,
            String logoPath,
            String logoMarkPath,
            String heroPath,
            String ogImagePath,
            String faviconPath
    ) {}
}
