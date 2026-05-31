package com.celtech.solutions.cgsKitchen.services.user;

import com.celtech.solutions.cgsKitchen.models.user.Address;
import com.celtech.solutions.cgsKitchen.repositories.user.AddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saved-address CRUD with duplicate detection.
 *
 * <p>Enforces the "only one primary" invariant: marking an address
 * primary demotes any other primary for the same user. (Mongo doesn't
 * give us real transactions here, so we accept a tiny window where two
 * addresses could both be primary — last-writer-wins, harmless.)
 *
 * <p>Enforces "no duplicate addresses per user": when {@link #create}
 * detects an existing address with the same postal code, same first
 * numeric token in line1, and same line2 (null-blank equivalent), it
 * returns the existing record instead of creating a second copy. Callers
 * can detect this case by comparing the returned id to a known-fresh
 * value, or via {@link CreateResult} from {@link #createWithResult}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

    /** Matches the first run of digits in a string. "133 Hoover Cir" → "133". */
    private static final Pattern FIRST_NUMERIC = Pattern.compile("\\d+");

    private final AddressRepository addresses;

    public List<Address> findByUser(String userId) {
        return addresses.findByUserIdOrderByPrimaryDescUpdatedAtDesc(userId);
    }

    public Optional<Address> findByIdForUser(String addressId, String userId) {
        return addresses.findById(addressId)
                .filter(a -> userId.equals(a.getUserId()));
    }

    public Optional<Address> findPrimary(String userId) {
        return addresses.findByUserIdAndPrimaryTrue(userId);
    }

    /**
     * Create a new address. If a duplicate is detected (same postal,
     * same street number, same unit), the existing record is returned
     * instead of creating a new one. If {@code makePrimary} is true,
     * the existing duplicate is promoted to primary.
     */
    public Address create(String userId, Address input, boolean makePrimary) {
        return createWithResult(userId, input, makePrimary).address();
    }

    /**
     * Same as {@link #create} but reports whether the returned record
     * was newly inserted or an existing duplicate. Controllers use this
     * to surface a "using your saved address" flash message.
     */
    public CreateResult createWithResult(String userId, Address input, boolean makePrimary) {
        Optional<Address> existing = findDuplicate(userId, input);
        if (existing.isPresent()) {
            Address dup = existing.get();
            log.info("Duplicate address detected for user {} (existing id={})",
                    userId, dup.getId());
            if (makePrimary && !dup.isPrimary()) {
                demoteCurrentPrimary(userId);
                dup.setPrimary(true);
                dup = addresses.save(dup);
            }
            return new CreateResult(dup, false);
        }

        boolean isFirst = addresses.findByUserIdOrderByPrimaryDescUpdatedAtDesc(userId).isEmpty();
        boolean primary = isFirst || makePrimary;

        if (primary && !isFirst) {
            demoteCurrentPrimary(userId);
        }

        Address toSave = Address.builder()
                .userId(userId)
                .label(input.getLabel())
                .line1(input.getLine1())
                .line2(input.getLine2())
                .city(input.getCity())
                .state(input.getState())
                .postalCode(input.getPostalCode())
                .country(input.getCountry() == null ? "US" : input.getCountry())
                .notes(input.getNotes())
                .primary(primary)
                .build();

        Address saved = addresses.save(toSave);
        log.info("Created address {} for user {} (primary={})", saved.getId(), userId, primary);
        return new CreateResult(saved, true);
    }

    public Address update(String addressId, String userId, Address input) {
        Address existing = findByIdForUser(addressId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Address not found"));
        existing.setLabel(input.getLabel());
        existing.setLine1(input.getLine1());
        existing.setLine2(input.getLine2());
        existing.setCity(input.getCity());
        existing.setState(input.getState());
        existing.setPostalCode(input.getPostalCode());
        if (input.getCountry() != null) existing.setCountry(input.getCountry());
        existing.setNotes(input.getNotes());
        return addresses.save(existing);
    }

    /** Mark this address primary and demote any other primary for the same user. */
    public Address makePrimary(String addressId, String userId) {
        Address target = findByIdForUser(addressId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Address not found"));
        if (target.isPrimary()) return target;

        demoteCurrentPrimary(userId);
        target.setPrimary(true);
        return addresses.save(target);
    }

    public void delete(String addressId, String userId) {
        Address existing = findByIdForUser(addressId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Address not found"));
        boolean wasPrimary = existing.isPrimary();
        addresses.delete(existing);

        // If we deleted the primary, promote the most-recently-updated remaining.
        if (wasPrimary) {
            findByUser(userId).stream().findFirst().ifPresent(a -> {
                a.setPrimary(true);
                addresses.save(a);
                log.info("Promoted address {} to primary after delete", a.getId());
            });
        }
    }

    // ================================================================
    //  Duplicate detection
    // ================================================================

    /**
     * Considered duplicate when:
     * <ul>
     *   <li>Same postal code (case- and whitespace-normalized)</li>
     *   <li>Same first numeric token in line1 ("133 Hoover Cir" → "133")</li>
     *   <li>Same line2, treating null/blank as equivalent</li>
     * </ul>
     * <p>This catches the common "user typed it slightly different" case
     * (different spacing, "Cir" vs "Circle", etc.) without being so
     * strict that legitimately distinct addresses get merged. Two
     * apartments at the same building distinguish via line2.
     */
    private Optional<Address> findDuplicate(String userId, Address input) {
        String inputPostal = normalizePostal(input.getPostalCode());
        String inputNumber = firstNumeric(input.getLine1());
        String inputUnit = normalizeUnit(input.getLine2());

        if (inputPostal == null || inputNumber == null) {
            // Not enough info to match — don't dedup.
            return Optional.empty();
        }

        return addresses.findByUserIdOrderByPrimaryDescUpdatedAtDesc(userId).stream()
                .filter(existing -> inputPostal.equals(normalizePostal(existing.getPostalCode()))
                        && inputNumber.equals(firstNumeric(existing.getLine1()))
                        && inputUnit.equals(normalizeUnit(existing.getLine2())))
                .findFirst();
    }

    private static String normalizePostal(String postal) {
        if (postal == null) return null;
        String trimmed = postal.trim();
        if (trimmed.isEmpty()) return null;
        // Take just the first 5 digits, so "40165" matches "40165-6107".
        Matcher m = Pattern.compile("\\d{5}").matcher(trimmed);
        return m.find() ? m.group() : trimmed.toLowerCase();
    }

    private static String firstNumeric(String line1) {
        if (line1 == null) return null;
        Matcher m = FIRST_NUMERIC.matcher(line1);
        return m.find() ? m.group() : null;
    }

    private static String normalizeUnit(String line2) {
        if (line2 == null || line2.isBlank()) return "";
        return line2.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private void demoteCurrentPrimary(String userId) {
        addresses.findByUserIdAndPrimaryTrue(userId).ifPresent(p -> {
            p.setPrimary(false);
            addresses.save(p);
        });
    }

    /**
     * Outcome of an address create call.
     *
     * @param address   the address record (newly created or pre-existing duplicate)
     * @param created   true if a new record was inserted; false if a duplicate
     *                  was found and returned
     */
    public record CreateResult(Address address, boolean created) {
        public boolean isExistingDuplicate() { return !created; }
    }
}