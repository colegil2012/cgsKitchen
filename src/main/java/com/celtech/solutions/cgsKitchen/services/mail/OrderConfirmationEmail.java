package com.celtech.solutions.cgsKitchen.services.mail;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.mail.MailMessage;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends the "your order is confirmed" receipt email. Trigger this once an
 * order transitions to PAID (e.g. from the Stripe webhook's markPaid path).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderConfirmationEmail {

    private final MailService mailService;
    private final EmailRenderer renderer;
    private final AppProperties props;

    public void send(Order order) {
        if (order == null || order.getCustomerEmail() == null
                || order.getCustomerEmail().isBlank()) {
            log.warn("Skipping order confirmation email — no recipient for order {}",
                    order == null ? "null" : order.getId());
            return;
        }

        Map<String, Object> model = new HashMap<>();
        model.put("brand", props.storefront());
        model.put("baseUrl", props.baseUrl());
        model.put("order", order);
        model.put("orderUrl", props.baseUrl() + "/order/confirm?order_id=" + order.getId());

        String html = renderer.render("mail/order-confirmation", model);

        mailService.send(MailMessage.builder()
                .to(order.getCustomerEmail())
                .subject("Order confirmed")
                .html(html)
                .text("Thanks for your order! Reference: " + order.getId()
                        + ". Total: $" + String.format("%.2f", order.getTotalCents() / 100.0))
                .build());
    }
}