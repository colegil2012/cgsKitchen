package com.celtech.solutions.cgsKitchen.services.mail;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.mail.MailMessage;
import com.celtech.solutions.cgsKitchen.models.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Sends the address-confirmation email after registration. The {@code token}
 * is a single-use, time-limited value persisted against the user; the link
 * lands on a controller that verifies it and flips {@code emailVerified}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationEmail {

    private final MailService mailService;
    private final EmailRenderer renderer;
    private final AppProperties props;

    public void send(User user, String token) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("Skipping verification email — no recipient");
            return;
        }

        String link = props.baseUrl() + "/verify-email?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);

        Map<String, Object> model = new HashMap<>();
        model.put("brand", props.storefront());
        model.put("baseUrl", props.baseUrl());
        model.put("displayName", user.getDisplayName());
        model.put("verifyUrl", link);

        String html = renderer.render("mail/email-verification", model);

        mailService.send(MailMessage.builder()
                .to(user.getEmail())
                .subject("Confirm your Email")
                .html(html)
                .text("Confirm your email: " + link)
                .build());
    }
}