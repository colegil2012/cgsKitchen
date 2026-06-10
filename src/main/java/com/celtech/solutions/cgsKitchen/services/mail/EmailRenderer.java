package com.celtech.solutions.cgsKitchen.services.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Renders Thymeleaf templates under {@code templates/email/} to an HTML
 * string for use as a {@link com.celtech.solutions.cgsKitchen.models.mail.MailMessage}
 * body.
 *
 * <p>Uses the same {@link TemplateEngine} Spring Boot auto-configures for
 * the web views, so {@code @beanName} helpers (e.g. {@code @imageUrls})
 * and standard dialects are available inside email templates too.
 */
@Component
@RequiredArgsConstructor
public class EmailRenderer {

    private final TemplateEngine templateEngine;

    /**
     * @param template logical name relative to the templates root, e.g.
     *                 {@code "email/order-confirmation"}
     * @param model    variables exposed to the template
     */
    public String render(String template, Map<String, Object> model) {
        Context ctx = new Context();
        ctx.setVariables(model);
        return templateEngine.process(template, ctx);
    }
}