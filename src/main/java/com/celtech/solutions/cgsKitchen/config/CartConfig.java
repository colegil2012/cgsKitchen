package com.celtech.solutions.cgsKitchen.config;

import com.celtech.solutions.cgsKitchen.models.Cart;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.web.context.WebApplicationContext;

/**
 * Registers {@link Cart} as a session-scoped bean. Each visitor gets
 * their own cart, automatically cleaned up when their session expires.
 *
 * <p>The CGLib proxy lets controllers inject Cart directly even though
 * controllers are singletons.
 */
@Configuration
public class CartConfig {

    @Bean
    @Scope(value = WebApplicationContext.SCOPE_SESSION,
           proxyMode = ScopedProxyMode.TARGET_CLASS)
    public Cart cart() {
        return new Cart();
    }
}
