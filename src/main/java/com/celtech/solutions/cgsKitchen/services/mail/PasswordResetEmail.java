package com.celtech.solutions.cgsKitchen.services.mail;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.mail.MailMessage;
import com.celtech.solutions.cgsKitchen.models.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Sends the "reset your password" email. The {@code token} is a single-use,
 * short-lived value persisted against the user; the link lands on a
 * controller that validates it and shows the new-password form.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetEmail {

    private final MailService mailService;
    private final EmailRenderer renderer;
    private final AppProperties props;

    /** Minutes the reset link stays valid — used only for display copy. */
    private static final long EXPIRY_MINUTES = 30;

    public void send(User user, String token) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("Skipping password reset email — no recipient");
            return;
        }

        String link = props.baseUrl() + "/reset-password?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);

        Map<String, Object> model = new HashMap<>();
        model.put("brand", props.storefront());
        model.put("baseUrl", props.baseUrl());
        model.put("displayName", user.getDisplayName());
        model.put("resetUrl", link);
        model.put("expiryMinutes", EXPIRY_MINUTES);

        String html = renderer.render("mail/password-reset", model);

        mailService.send(MailMessage.builder()
                .to(user.getEmail())
                .subject("Reset your Password")
                .html(html)
                .text("Reset your password (valid " + EXPIRY_MINUTES + " min): " + link)
                .build());
    }
}