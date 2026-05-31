package com.celtech.solutions.cgsKitchen.controllers.user;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.services.user.TurnstileService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

/**
 * Login / register pages.
 *
 * <p>Login itself is handled by Spring Security's filter — we just
 * render the page. Registration is our own POST.
 */
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final TurnstileService turnstile;
    private final AppProperties props;

    @ModelAttribute("turnstileSiteKey")
    public String turnstileSiteKey() {
        return props.captcha().isConfigured() ? props.captcha().siteKey() : null;
    }

    // ---------- Login ----------

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) Long locked,
                            @RequestParam(required = false) String registered,
                            Model model) {
        if (locked != null) {
            // Account is temporarily locked from too many failed attempts.
            // We say "this account" not "your account" — the visitor may not
            // actually own this email; we don't confirm or deny existence.
            model.addAttribute("error",
                    "This account is temporarily locked after too many failed sign-in attempts. " +
                            "Try again in " + locked + " minute" + (locked == 1 ? "" : "s") + ".");
        } else if (error != null) {
            model.addAttribute("error", "Email or password didn't match. Try again.");
        }
        if (registered != null) {
            model.addAttribute("notice", "Account created — sign in to continue.");
        }
        return "auth/login";
    }

    // ---------- Register ----------

    @GetMapping("/register")
    public String registerPage(Model model) {
        if (!model.containsAttribute("registerForm")) {
            model.addAttribute("registerForm", new RegisterForm());
        }
        return "auth/register";
    }

    @PostMapping("/register")
    public String submitRegister(
            @Valid @ModelAttribute("registerForm") RegisterForm form,
            BindingResult binding,
            @RequestParam(value = "cf-turnstile-response", required = false) String captchaToken,
            HttpServletRequest request,
            Model model
    ) {
        if (!form.getPassword().equals(form.getPasswordConfirm())) {
            binding.rejectValue("passwordConfirm", "mismatch", "Passwords don't match.");
        }
        if (!turnstile.verify(captchaToken, request.getRemoteAddr())) {
            model.addAttribute("error", "Captcha check failed. Please try again.");
            return "auth/register";
        }
        if (binding.hasErrors()) {
            return "auth/register";
        }

        try {
            userService.register(form.getEmail(), form.getPassword(),
                    form.getDisplayName(), form.getPhone());
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            return "auth/register";
        }
        return "redirect:/login?registered";
    }

    // ---------- Form DTO ----------

    @Data
    public static class RegisterForm {

        @NotBlank(message = "Email is required.")
        @Email(message = "That doesn't look like a valid email.")
        @Size(max = 254)
        private String email;

        @NotBlank(message = "Choose a password.")
        @Size(min = 10, max = 100, message = "Use at least 10 characters.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Include at least one letter and one number."
        )
        private String password;

        @NotBlank(message = "Confirm your password.")
        private String passwordConfirm;

        @Size(max = 80)
        private String displayName;

        @Pattern(
                regexp = "^$|^[+0-9 ()\\-]{7,20}$",
                message = "Use digits, spaces, +, (, ), and - only."
        )
        private String phone;
    }
}