package com.celtech.solutions.cgsKitchen.config;

import com.celtech.solutions.cgsKitchen.models.storefront.event.Event;
import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.services.storefront.shop.CartService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Injects {@code currentEvent} and {@code nextEvent} into every Thymeleaf
 * model rendered by Spring MVC.
 *
 * <p>Why this exists: the event banner lives in the layout fragment and
 * needs to know the current event on every page render. Without this
 * advice, each controller would have to manually add the event to its
 * model — easy to forget, error-prone. With this advice, the layout
 * just references {@code ${currentEvent}} and it's always there (or
 * null if no event is open).
 *
 * <p>Caching: lookups are cheap (one indexed Mongo query) but they
 * happen on every page. A 60-second TTL caches the result so a single
 * burst of page views hits Mongo at most once per minute. The cache is
 * invalidated by time — there's no event-side push, so activation
 * changes can take up to 60 seconds to appear. Acceptable trade-off.
 *
 * <p>The {@code @ControllerAdvice(annotations = Controller.class)} would
 * scope this to @Controller only and exclude @RestController; we use
 * the unscoped form because the @RestController API endpoints don't
 * use model attributes anyway — Jackson serialization ignores them.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAttributes {

    private final CartService cartService;
    private final UserService userService;

    private final AppProperties props;
    private final ImageUrls imageUrls;

    /** Cache lifetime. 60 seconds gives near-instant updates without hammering Mongo. */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final EventService eventService;

    /** Volatile reference to the cached snapshot. */
    private final AtomicReference<Snapshot> cache = new AtomicReference<>(Snapshot.empty());

    /************************************************************************************
     * Add properties to every Thymeleaf model.
     ************************************************************************************/

    @ModelAttribute("brand")
    public AppProperties.Storefront brand() {
        return props.storefront();
    }

    @ModelAttribute("currentEvent")
    public Event currentEvent() {
        return getSnapshot().current;
    }

    @ModelAttribute("nextEvent")
    public Event nextEvent() {
        return getSnapshot().next;
    }

    @ModelAttribute("imageUrls")
    public ImageUrls imageUrls() {
        return imageUrls;
    }

    private Snapshot getSnapshot() {
        Snapshot existing = cache.get();
        Instant now = Instant.now();
        if (existing.isFresh(now)) {
            return existing;
        }
        Event current = eventService.findCurrentlyOpen().orElse(null);
        Event next = current != null
                ? null
                : eventService.findNextScheduled(zone()).orElse(null);
        Snapshot fresh = new Snapshot(current, next, now);
        cache.set(fresh);
        log.debug("Refreshed event snapshot: current={}, next={}",
                current == null ? "none" : current.getId(),
                next == null ? "none" : next.getId());
        return fresh;
    }

    /**
     * Populate {@code ${cart}} for the nav cart badge on every page.
     * <p>Resolution rule matches {@code CartController.currentCart}:
     * authenticated → user cart; otherwise → guest cart by cookie. If
     * neither resolves (no cookie attribute, no auth), returns null and
     * the badge renders as empty.
     *
     * <p>This is computed per-request. For a heavy-traffic site we'd
     * cache or use a request-scoped bean; for a food-truck pre-order app
     * the per-request lookup (one indexed Mongo query) is fine.
     */
    /**
     * Populate {@code ${cart}} for the nav cart badge on every page.
     * Authenticated → user cart; otherwise → guest cart by cookie.
     * Null if neither resolves; template handles null.
     */
    @ModelAttribute("cart")
    public Cart cart(HttpServletRequest request, Authentication auth) {
        try {
            // Authenticated → user cart
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getPrincipal())) {
                String email = auth.getName();
                User user = userService.findByEmail(email).orElse(null);
                if (user != null) {
                    return cartService.findOrCreateForUser(user.getId());
                }
            }
            // Guest → cart by cookie. Cookie set by CartCookieFilter.
            Object cookieAttr = request.getAttribute(CartCookieFilter.REQUEST_ATTR);
            if (cookieAttr != null) {
                return cartService.findOrCreateForGuest(cookieAttr.toString());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private ZoneId zone() {
        String tz = props.storefront().timezone();
        return ZoneId.of(tz == null || tz.isBlank() ? "America/New_York" : tz);
    }

    record Snapshot(Event current, Event next, Instant loadedAt) {
        static Snapshot empty() {
            return new Snapshot(null, null, Instant.EPOCH);
        }
        boolean isFresh(Instant now) {
            return Duration.between(loadedAt, now).compareTo(CACHE_TTL) < 0;
        }
    }

}