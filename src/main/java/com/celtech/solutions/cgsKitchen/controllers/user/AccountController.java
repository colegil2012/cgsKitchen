package com.celtech.solutions.cgsKitchen.controllers.user;

import com.celtech.solutions.cgsKitchen.models.user.Address;
import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.repositories.user.PaymentMethodRepository;
import com.celtech.solutions.cgsKitchen.services.user.AddressService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import com.celtech.solutions.cgsKitchen.services.mail.EmailVerificationEmail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * The signed-in customer area: profile, addresses, saved payment methods.
 *
 * <p>All endpoints use the form-post + redirect-after-post pattern with
 * flash attributes. The Thymeleaf templates supply CSRF tokens via
 * {@code th:action}; no separate JSON API is needed here.
 *
 * <p>Payment methods are render-only on this page — adding/removing happens
 * through Stripe (Elements at checkout, or detach via the Stripe Dashboard)
 * and the local mirror is kept in sync by webhook handlers.
 */
@Controller
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

    private final UserService userService;
    private final AddressService addressService;
    private final PaymentMethodRepository paymentMethods;
    private final EmailVerificationEmail emailVerificationEmail;

    private User currentUser(UserDetails principal) {
        return userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Signed-in user not found"));
    }

    // ---------- Overview ----------

    @GetMapping
    public String overview(@AuthenticationPrincipal UserDetails principal, Model model) {
        User user = currentUser(principal);
        List<Address> userAddresses = addressService.findByUser(user.getId());

        model.addAttribute("user", user);
        model.addAttribute("addresses", userAddresses);
        model.addAttribute("paymentMethods",
                paymentMethods.findByUserIdOrderByDefaultMethodDescUpdatedAtDesc(user.getId()));

        if (!model.containsAttribute("profileForm")) {
            ProfileForm pf = new ProfileForm();
            pf.setDisplayName(user.getDisplayName());
            pf.setPhone(user.getPhone());
            model.addAttribute("profileForm", pf);
        }
        if (!model.containsAttribute("addressForm")) {
            model.addAttribute("addressForm", new AddressForm());
        }
        if (!model.containsAttribute("passwordForm")) {
            model.addAttribute("passwordForm", new PasswordForm());
        }
        return "account/index";
    }

    // ---------- Profile ----------

    @PostMapping("/profile")
    public String updateProfile(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @ModelAttribute("profileForm") ProfileForm form,
            BindingResult binding,
            RedirectAttributes redirect
    ) {
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", "Please fix the highlighted fields.");
            redirect.addFlashAttribute("profileForm", form);
            return "redirect:/account";
        }
        User u = currentUser(principal);
        userService.updateProfile(u.getId(), form.getDisplayName(), form.getPhone());
        redirect.addFlashAttribute("notice", "Profile updated.");
        return "redirect:/account";
    }

    // ---------- Password ----------

    @PostMapping("/password")
    public String changePassword(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @ModelAttribute("passwordForm") PasswordForm form,
            BindingResult binding,
            RedirectAttributes redirect
    ) {
        if (!form.getNewPassword().equals(form.getNewPasswordConfirm())) {
            binding.rejectValue("newPasswordConfirm", "mismatch", "Passwords don't match.");
        }
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", "Please fix the highlighted fields.");
            return "redirect:/account";
        }
        User u = currentUser(principal);
        try {
            userService.changePassword(u.getId(), form.getCurrentPassword(), form.getNewPassword());
            redirect.addFlashAttribute("notice", "Password updated.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/account";
    }

    // ---------- Email verification ----------

    @PostMapping("/resend-verification")
    public String resendVerification(
            @AuthenticationPrincipal UserDetails principal,
            RedirectAttributes redirect
    ) {
        User u = currentUser(principal);
        if (u.isEmailVerified()) {
            redirect.addFlashAttribute("notice", "Your email is already confirmed.");
            return "redirect:/account";
        }
        String token = userService.issueVerificationToken(u.getId());
        emailVerificationEmail.send(u, token);
        redirect.addFlashAttribute("notice",
                "Confirmation email sent — check your inbox.");
        return "redirect:/account";
    }

    // ---------- Addresses ----------
    // Delegate to AddressService so the "only one primary" and "no
    // duplicates" invariants live in one place. Controller stays thin
    // and form-flow focused.

    @PostMapping("/addresses")
    public String addAddress(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @ModelAttribute("addressForm") AddressForm form,
            BindingResult binding,
            RedirectAttributes redirect,
            @RequestParam(required = false) String returnTo
    ) {
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", "Please fix the highlighted fields.");
            redirect.addFlashAttribute("addressForm", form);
            return "redirect:" + redirectTarget(returnTo);
        }
        User u = currentUser(principal);
        Address toCreate = Address.builder()
                .label(form.getLabel())
                .line1(form.getLine1())
                .line2(form.getLine2())
                .city(form.getCity())
                .state(form.getState())
                .postalCode(form.getPostalCode())
                .country(form.getCountry())
                .notes(form.getNotes())
                .build();

        var result = addressService.createWithResult(u.getId(), toCreate, form.isPrimary());

        if (result.isExistingDuplicate()) {
            String label = result.address().getLabel();
            redirect.addFlashAttribute("notice",
                    label != null && !label.isBlank()
                            ? "Using your saved address \"" + label + "\"."
                            : "Using your saved address.");
        } else {
            redirect.addFlashAttribute("notice", "Address saved.");
        }
        return "redirect:" + redirectTarget(returnTo) + "#address-" + result.address().getId();
    }

    @PostMapping("/addresses/{id}/delete")
    public String deleteAddress(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String id,
            RedirectAttributes redirect,
            @RequestParam(required = false) String returnTo
    ) {
        User u = currentUser(principal);
        try {
            addressService.delete(id, u.getId());
            redirect.addFlashAttribute("notice", "Address removed.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:" + redirectTarget(returnTo);
    }

    @PostMapping("/addresses/{id}/primary")
    public String makePrimary(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String id,
            RedirectAttributes redirect,
            @RequestParam(required = false) String returnTo
    ) {
        User u = currentUser(principal);
        try {
            addressService.makePrimary(id, u.getId());
            redirect.addFlashAttribute("notice", "Primary address updated.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:" + redirectTarget(returnTo);
    }

    /**
     * Lets the address forms post from either /account or /account/addresses
     * and redirect back to wherever they came from. Defaults to /account.
     */
    private String redirectTarget(String returnTo) {
        if ("/account/addresses".equals(returnTo)) return "/account/addresses";
        return "/account";
    }

    // ---------- Form DTOs ----------

    @Data
    public static class ProfileForm {
        @Size(max = 80) private String displayName;
        @Pattern(regexp = "^$|^[+0-9 ()\\-]{7,20}$",
                message = "Use digits, spaces, +, (, ), and - only.")
        private String phone;
    }

    @Data
    public static class PasswordForm {
        @NotBlank private String currentPassword;
        @NotBlank
        @Size(min = 10, max = 100, message = "Use at least 10 characters.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Include a letter and a number.")
        private String newPassword;
        @NotBlank private String newPasswordConfirm;
    }

    @Data
    public static class AddressForm {
        @Size(max = 40)  private String label;
        @NotBlank @Size(max = 120) private String line1;
        @Size(max = 120) private String line2;
        @NotBlank @Size(max = 80)  private String city;
        @NotBlank @Size(max = 40)  private String state;
        @NotBlank @Size(max = 20)  private String postalCode;
        @Size(max = 2)             private String country;
        @Size(max = 240) private String notes;
        private boolean primary;
    }
}