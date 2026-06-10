package com.celtech.solutions.cgsKitchen.controllers.user;

import com.celtech.solutions.cgsKitchen.services.mail.PasswordResetEmail;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service account recovery: email verification + the forgot/reset
 * password flow. All endpoints here are public (a locked-out or
 * unverified user must be able to reach them without signing in).
 */
@Controller
@RequiredArgsConstructor
public class AccountRecoveryController {

    private final UserService userService;
    private final PasswordResetEmail passwordResetEmail;

    // ---------- Email verification ----------

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam(required = false) String token, Model model) {
        boolean verified = userService.verifyEmail(token).isPresent();
        model.addAttribute("verified", verified);
        return "auth/verify-email-result";
    }

    // ---------- Forgot password (request a link) ----------

    @GetMapping("/forgot-password")
    public String forgotPasswordPage() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String submitForgotPassword(@RequestParam String email, Model model) {
        // Always show the same neutral message — never reveal whether the
        // email maps to a real account (avoids account enumeration).
        userService.issuePasswordResetToken(email)
                .ifPresent(u -> passwordResetEmail.send(u, u.getPasswordResetToken()));
        model.addAttribute("submitted", true);
        return "auth/forgot-password";
    }

    // ---------- Reset password (consume the link) ----------

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam(required = false) String token, Model model) {
        boolean valid = userService.findByValidPasswordResetToken(token).isPresent();
        model.addAttribute("validToken", valid);
        model.addAttribute("token", token);
        if (!model.containsAttribute("resetForm")) {
            model.addAttribute("resetForm", new ResetForm());
        }
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String submitResetPassword(@ModelAttribute("resetForm") ResetForm form, Model model) {
        // Light validation; the form bean handles the password rule, here we
        // just confirm the two entries match before consuming the token.
        if (form.getPassword() == null || !form.getPassword().equals(form.getPasswordConfirm())) {
            model.addAttribute("validToken", true);
            model.addAttribute("token", form.getToken());
            model.addAttribute("error", "Passwords don't match.");
            return "auth/reset-password";
        }

        boolean ok = userService.resetPassword(form.getToken(), form.getPassword()).isPresent();
        if (!ok) {
            model.addAttribute("validToken", false);
            model.addAttribute("token", form.getToken());
            model.addAttribute("error",
                    "That reset link is invalid or has expired. Request a new one.");
            return "auth/reset-password";
        }
        return "redirect:/login?reset";
    }

    // ---------- Form DTO ----------

    @Data
    public static class ResetForm {
        @NotBlank
        private String token;

        @NotBlank(message = "Choose a password.")
        @Size(min = 10, max = 100, message = "Use at least 10 characters.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Include at least one letter and one number."
        )
        private String password;

        @NotBlank(message = "Confirm your password.")
        private String passwordConfirm;
    }
}