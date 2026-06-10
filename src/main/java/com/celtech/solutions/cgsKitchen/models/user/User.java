package com.celtech.solutions.cgsKitchen.models.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A storefront account. Email is the natural key — used for login,
 * receipts, and Stripe customer lookup.
 *
 * <p>Password is BCrypt-hashed. We never store plaintext.
 *
 * <p>{@code stripeCustomerId} is populated lazily on the user's first
 * authenticated checkout. {@code emailVerified} stays false until the
 * email confirmation flow lands.
 *
 * <p><b>Lockout fields</b> ({@code failedLoginCount}, {@code lockedUntil})
 * implement per-account brute-force protection. After 5 failed attempts
 * within the lockout window, the account is locked for 15 minutes.
 * Cleared on successful login. See {@link com.celtech.solutions.cgsKitchen.services.user.UserService}
 * for the policy logic.
 */
@Document(collection = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    /** BCrypt hash. */
    private String passwordHash;

    private String displayName;
    private String phone;

    /** Stripe Customer id (cus_...). Null until first authenticated checkout. */
    private String stripeCustomerId;

    @Builder.Default
    private boolean emailVerified = false;

    /** Single use token for email validation. */
    private String emailValidationToken;
    private Instant emailValidationTokenExpiresAt;

    /** Single use token for password reset. */
    private String passwordResetToken;
    private Instant passwordResetTokenExpiresAt;

    /** Soft-disable without deleting the record (refunds, audit). */
    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    /**
     * Running count of consecutive failed login attempts. Reset to 0 on
     * a successful login. Defaults to 0 for new accounts.
     */
    @Builder.Default
    private int failedLoginCount = 0;
    private Instant lockedUntil;

    /** True if currently locked out (defensive — UserDetails maps this to accountNonLocked). */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    public enum Role {
        CUSTOMER,
        ADMIN
    }
}