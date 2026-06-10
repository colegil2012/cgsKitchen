package com.celtech.solutions.cgsKitchen.services.mail;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.mail.MailMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class JavaMailService implements MailService {

    private final JavaMailSender mailSender;
    private final AppProperties props;

    @Override
    @Async
    public void send(MailMessage message) {
        if (!props.mail().enabled()) {
            log.info("Mail subsystem disabled; would have sent to={}, subject='{}'",
                    message.getTo(), message.getSubject());
            return;
        }
        if (message.getTo() == null || message.getTo().isBlank()) {
            log.warn("Refusing to send mail with empty recipient: subject='{}'",
                    message.getSubject());
            return;
        }

        String from = message.getFromOverride() != null ? message.getFromOverride() : props.mail().from();

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            // multipart=true so we can attach plain-text + html alternatives;
            // UTF-8 so non-ASCII characters in names and item titles render.
            MimeMessageHelper helper = new MimeMessageHelper(
                    mime, true, StandardCharsets.UTF_8.name());

            helper.setFrom(buildFromAddress(from));
            if (props.mail().replyTo() != null && !props.mail().replyTo().isBlank()) {
                helper.setReplyTo(props.mail().replyTo());
            }
            helper.setTo(message.getTo());
            helper.setSubject(message.getSubject());
            // text first, html second — Spring picks the html part as the visible body
            // and the text part as the alternative for plain-text-only clients.
            if (message.getText() != null) {
                helper.setText(message.getText(), message.getHtml());
            } else {
                helper.setText(message.getHtml(), true);
            }

            mailSender.send(mime);
            log.info("Mail sent: to={}, subject='{}'", message.getTo(), message.getSubject());

        } catch (MessagingException | UnsupportedEncodingException ex) {
            log.error("Mail send failed: to={}, subject='{}', error={}",
                    message.getTo(), message.getSubject(), ex.getMessage(), ex);
        } catch (Exception ex) {
            // Catch-all so any transport hiccup never propagates to the calling thread.
            log.error("Unexpected mail send error: to={}, subject='{}'",
                    message.getTo(), message.getSubject(), ex);
        }
    }

    /**
     * Build a "from" with the configured friendly name, e.g.
     * {@code "Celtech General Store" <support@celtechgs.com>}. Falls back to
     * the bare address if the name isn't configured.
     */
    private InternetAddress buildFromAddress(String address) throws UnsupportedEncodingException {
        if (props.mail().fromName() == null || props.mail().fromName().isBlank()) {
            try {
                return new InternetAddress(address);
            } catch (jakarta.mail.internet.AddressException e) {
                throw new UnsupportedEncodingException(e.getMessage());
            }
        }
        return new InternetAddress(address, props.mail().fromName(), StandardCharsets.UTF_8.name());
    }
}
