package com.celtech.solutions.cgsKitchen.services.user;

import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.repositories.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;

/**
 * User-level business logic, including the per-account brute-force
 * lockout policy.
 *
 * <p>Lockout policy: after {@link #MAX_FAILED_ATTEMPTS} consecutive
 * failed logins, the account is locked for {@link #LOCKOUT_DURATION}.
 * Failed-count is reset to 0 on successful login. The lockedUntil
 * field is set to a wall-clock instant; the {@link User#isLocked}
 * check is time-bounded so locks expire automatically without any
 * sweep needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** Attempts before lockout triggers. */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /** How long an account stays locked after threshold is reached. */
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    public static final Duration VERIFY_EMAIL_TOKEN_DURATION = Duration.ofHours(24);
    public static final Duration RESET_PASSWORD_TOKEN_DURATION = Duration.ofMinutes(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    /**
     * Self-registration entry point. Throws {@link IllegalArgumentException}
     * if the email is already taken — the controller turns that into a form
     * error.
     */
    public User register(String email, String rawPassword, String displayName, String phone) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new IllegalArgumentException("An account with that email already exists.");
        }
        User user = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .displayName(displayName == null ? null : displayName.trim())
                .phone(phone == null ? null : phone.trim())
                .roles(EnumSet.of(User.Role.CUSTOMER))
                .enabled(true)
                .emailVerified(false)
                .build();
        User saved = users.save(user);
        log.info("Registered new user {} (id={})", saved.getEmail(), saved.getId());
        return saved;
    }

    // ================================================================
    //  Email verification
    // ================================================================

    /**
     * Generate a fresh verification token, persist it on the user (with a
     * 24h expiry), and return the raw token so the caller can email it.
     * Overwrites any existing token — only the latest link works.
     */
    public String issueVerificationToken(String userId) {
        User u = users.findById(userId).orElseThrow();
        String token = newToken();
        u.setEmailValidationToken(token);
        u.setEmailValidationTokenExpiresAt(Instant.now().plus(VERIFY_EMAIL_TOKEN_DURATION));
        users.save(u);
        return token;
    }

    /**
     * Consume a verification token: if it's valid and unexpired, flip
     * {@code emailVerified} and clear the token. Returns the verified user,
     * or empty if the token is unknown/expired.
     */
    public Optional<User> verifyEmail(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<User> maybe = users.findByEmailValidationToken(token);
        if (maybe.isEmpty()) return Optional.empty();

        User u = maybe.get();
        if (u.getEmailValidationTokenExpiresAt() == null
                || u.getEmailValidationTokenExpiresAt().isBefore(Instant.now())) {
            log.info("Expired verification token for {}", u.getEmail());
            return Optional.empty();
        }

        u.setEmailVerified(true);
        u.setEmailValidationToken(null);
        u.setEmailValidationTokenExpiresAt(null);
        users.save(u);
        log.info("Email verified for {}", u.getEmail());
        return Optional.of(u);
    }

    // ================================================================
    //  Password reset
    // ================================================================

    /**
     * Begin a password reset. Looks the user up by email; if found, issues a
     * short-lived token and returns the user (so the caller can email the
     * link). Returns empty if no such account — callers should NOT reveal
     * that difference to the requester (always show "if an account exists,
     * we sent a link").
     */
    public Optional<User> issuePasswordResetToken(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        Optional<User> maybe = users.findByEmailIgnoreCase(email.trim().toLowerCase());
        if (maybe.isEmpty()) return Optional.empty();

        User u = maybe.get();
        u.setPasswordResetToken(newToken());
        u.setPasswordResetTokenExpiresAt(Instant.now().plus(RESET_PASSWORD_TOKEN_DURATION));
        users.save(u);
        log.info("Issued password-reset token for {}", u.getEmail());
        return Optional.of(u);
    }

    /**
     * Validate a reset token without consuming it — used by the GET handler
     * to decide whether to show the new-password form or an "expired link"
     * message.
     */
    public Optional<User> findByValidPasswordResetToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return users.findByPasswordResetToken(token)
                .filter(u -> u.getPasswordResetTokenExpiresAt() != null
                        && u.getPasswordResetTokenExpiresAt().isAfter(Instant.now()));
    }

    /**
     * Complete a password reset: validate the token, set the new hashed
     * password, clear the token, and (defensively) clear any lockout.
     * Returns the updated user, or empty if the token is invalid/expired.
     */
    public Optional<User> resetPassword(String token, String newPassword) {
        Optional<User> maybe = findByValidPasswordResetToken(token);
        if (maybe.isEmpty()) {
            log.info("Rejected password reset — invalid/expired token");
            return Optional.empty();
        }
        User u = maybe.get();
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setPasswordResetToken(null);
        u.setPasswordResetTokenExpiresAt(null);
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        users.save(u);
        log.info("Password reset completed for {}", u.getEmail());
        return Optional.of(u);
    }

    /** URL-safe 256-bit random token. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Optional<User> findByEmail(String email) {
        return users.findByEmailIgnoreCase(email);
    }

    public Optional<User> findById(String id) {
        return users.findById(id);
    }

    public User updateProfile(String userId, String displayName, String phone) {
        User u = users.findById(userId).orElseThrow();
        u.setDisplayName(displayName == null ? null : displayName.trim());
        u.setPhone(phone == null ? null : phone.trim());
        return users.save(u);
    }

    public User changePassword(String userId, String currentPassword, String newPassword) {
        User u = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(currentPassword, u.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        return users.save(u);
    }

    /** Used by the checkout flow when we first see a signed-in user there. */
    public User attachStripeCustomerId(String userId, String stripeCustomerId) {
        User u = users.findById(userId).orElseThrow();
        u.setStripeCustomerId(stripeCustomerId);
        return users.save(u);
    }

    // ================================================================
    //  Brute-force lockout policy
    // ================================================================

    /**
     * Increment the failed-attempt counter for an email. If threshold
     * reached, set {@code lockedUntil} to now + duration. No-op if the
     * email doesn't map to an existing user — the lockout policy
     * intentionally only tracks real accounts.
     *
     * <p>Returns the user if found, empty otherwise (caller can use this
     * to log without revealing whether the email exists).
     */
    public Optional<User> recordFailedLogin(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        Optional<User> maybe = users.findByEmailIgnoreCase(email.trim().toLowerCase());
        if (maybe.isEmpty()) return Optional.empty();

        User u = maybe.get();
        int newCount = u.getFailedLoginCount() + 1;
        u.setFailedLoginCount(newCount);

        if (newCount >= MAX_FAILED_ATTEMPTS) {
            Instant until = Instant.now().plus(LOCKOUT_DURATION);
            u.setLockedUntil(until);
            log.warn("Account {} locked until {} after {} failed attempts",
                    u.getEmail(), until, newCount);
        } else {
            log.info("Failed login for {} ({}/{} attempts)",
                    u.getEmail(), newCount, MAX_FAILED_ATTEMPTS);
        }

        users.save(u);
        return Optional.of(u);
    }

    /**
     * Reset the failed-attempt counter on a successful login. Also clears
     * any stale lockedUntil (defensive — Spring shouldn't authenticate a
     * locked account, but if the wall-clock has passed the lock time we
     * want a clean slate).
     */
    public void recordSuccessfulLogin(String email) {
        if (email == null || email.isBlank()) return;
        users.findByEmailIgnoreCase(email.trim().toLowerCase()).ifPresent(u -> {
            if (u.getFailedLoginCount() > 0 || u.getLockedUntil() != null) {
                u.setFailedLoginCount(0);
                u.setLockedUntil(null);
                users.save(u);
                log.info("Reset failed-login counter for {}", u.getEmail());
            }
        });
    }

    /**
     * How many minutes remain on the lock, ceiling-rounded for display.
     * Returns 0 if not locked or already expired.
     */
    public long minutesRemainingOnLock(User user) {
        if (user.getLockedUntil() == null) return 0;
        Duration remaining = Duration.between(Instant.now(), user.getLockedUntil());
        if (remaining.isNegative() || remaining.isZero()) return 0;
        return (long) Math.ceil(remaining.toSeconds() / 60.0);
    }
}