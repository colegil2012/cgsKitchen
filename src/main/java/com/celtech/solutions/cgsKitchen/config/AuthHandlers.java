package com.celtech.solutions.cgsKitchen.config;

import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.services.storefront.shop.CartService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Authentication outcome handlers wired into the storefront login flow.
 *
 * <p><b>Success path:</b>
 * <ol>
 *   <li>Reset the failed-login counter on the user record</li>
 *   <li><b>Merge guest cart → user cart</b> (chunk 2 cutover). If the
 *       visitor had items in their cookie-keyed guest cart, those merge
 *       into their saved user cart. Silent merge: line items with the
 *       same product + selections sum quantities; different selections
 *       stay separate. The guest cart row is deleted; the cookie itself
 *       persists (will refer to a fresh empty guest cart on next logout
 *       or anonymous browse).</li>
 *   <li>Route admins to /admin and customers to /checkout — customers
 *       go straight to checkout because the most common reason to sign
 *       in is to complete an in-progress order, and they'll see their
 *       merged cart there.</li>
 * </ol>
 *
 * <p><b>Failure path:</b> distinguishes locked vs. other failures (see
 * inline comments on {@link #failure}).
 *
 * <p>This is a {@code @Component} so Spring constructs it with
 * {@link UserService} and {@link CartService} injected, separately from
 * {@link SecurityConfig}. That decoupling matters: SecurityConfig
 * defines {@code PasswordEncoder}, which {@link UserService} depends on.
 * If SecurityConfig also depended on UserService, the resulting cycle
 * would prevent startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthHandlers {

    private final UserService userService;
    private final CartService cartService;

    public AuthenticationSuccessHandler success() {
        return (HttpServletRequest request,
                HttpServletResponse response,
                Authentication authentication) -> {
            String email = authentication.getName();
            userService.recordSuccessfulLogin(email);

            // Merge guest cart (if any) into the user's persistent cart.
            // Best-effort: a failure here should NOT block login.
            try {
                mergeGuestCartOnLogin(request, email);
            } catch (Exception e) {
                log.warn("Cart merge on login failed for {} — proceeding with login anyway",
                        email, e);
            }

            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            response.sendRedirect(request.getContextPath() + (isAdmin ? "/admin" : "/checkout"));
        };
    }

    /**
     * If the request had a {@code cgsk_cart} cookie pointing at a guest
     * cart, merge its contents into the now-signed-in user's cart.
     * Silent on failure (handled by caller).
     */
    private void mergeGuestCartOnLogin(HttpServletRequest request, String email) {
        String cookieId = readCartCookie(request);
        if (cookieId == null) {
            return;  // No guest cart to merge
        }
        User user = userService.findByEmail(email).orElse(null);
        if (user == null) {
            log.warn("Auth success for {} but user record not found — skipping cart merge",
                    email);
            return;
        }
        cartService.mergeGuestIntoUser(cookieId, user.getId());
    }

    /**
     * Read the cart cookie value directly from the request. We don't go
     * through {@link CartCookieFilter#REQUEST_ATTR} because Spring
     * Security's filter chain may run before our filter set the
     * attribute on this particular request lifecycle; reading the cookie
     * directly is reliable regardless of filter order.
     */
    private String readCartCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (CartCookieFilter.COOKIE_NAME.equals(c.getName())) {
                String v = c.getValue();
                return (v == null || v.isBlank()) ? null : v;
            }
        }
        return null;
    }

    public AuthenticationFailureHandler failure() {
        return new AuthenticationFailureHandler() {
            @Override
            public void onAuthenticationFailure(@NonNull HttpServletRequest request,
                                                @NonNull HttpServletResponse response,
                                                @NonNull AuthenticationException exception)
                    throws IOException, ServletException {

                String email = request.getParameter("email");

                if (exception instanceof LockedException) {
                    long minutes = userService.findByEmail(email)
                            .map(userService::minutesRemainingOnLock)
                            .orElse(0L);
                    log.info("Locked-account login attempt for {} ({} min remaining)",
                            email, minutes);
                    response.sendRedirect(request.getContextPath()
                            + "/login?locked=" + Math.max(1, minutes));
                    return;
                }

                User u = userService.recordFailedLogin(email).orElse(null);

                if (u != null && u.isLocked()) {
                    long minutes = userService.minutesRemainingOnLock(u);
                    response.sendRedirect(request.getContextPath()
                            + "/login?locked=" + Math.max(1, minutes));
                    return;
                }

                response.sendRedirect(request.getContextPath() + "/login?error");
            }
        };
    }
}