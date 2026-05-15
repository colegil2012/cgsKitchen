package com.celtech.solutions.cgsKitchen.delivery;

/**
 * Abstraction over delivery-as-a-service providers (DoorDash Drive,
 * Uber Direct). Storefront and POS only see this interface.
 */
public interface DeliveryProvider {

    String name();

    DeliveryQuote quote(QuoteRequest request);

    DeliveryDispatch dispatch(DispatchRequest request);

    record QuoteRequest(
            String pickupAddress,
            String dropoffAddress,
            long orderTotalCents
    ) {}

    record DeliveryQuote(
            String quoteId,
            long feeCents,
            int etaMinutes,
            boolean accepted
    ) {
        public String getFeeDisplay() {
            return String.format("$%.2f", feeCents / 100.0);
        }
    }

    record DispatchRequest(
            String orderId,
            String pickupAddress,
            String dropoffAddress,
            String customerName,
            String customerPhone,
            long orderTotalCents,
            long tipCents
    ) {}

    record DeliveryDispatch(
            String externalDeliveryId,
            String trackingUrl,
            String status
    ) {}
}
