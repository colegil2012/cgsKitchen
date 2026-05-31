package com.celtech.solutions.cgsKitchen.services.user;

import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.repositories.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapts our {@link User} to Spring Security's {@link UserDetails}.
 * Login is by email; that's also what {@code principal.username} returns.
 *
 * <p>The {@code accountNonLocked} flag is wired to {@link User#isLocked},
 * so Spring throws a {@code LockedException} (distinct from
 * {@code BadCredentialsException}) when a locked account tries to sign in.
 * That distinction is used by {@code AuthFailureHandler} to show the
 * "try again in X minutes" message instead of "wrong password".
 */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));

        Set<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                .collect(Collectors.toSet());

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .accountLocked(user.isLocked())
                .authorities(authorities)
                .build();
    }
}