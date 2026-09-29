package com.zippay.common.security;

import com.zippay.auth.domain.Role;
import com.zippay.auth.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class CustomUserDetails implements UserDetails {
    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final Role role;

    public CustomUserDetails(User user) {
        this.userId = user.getId();
        this.email = user.getEmail().value();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
    }

    public Long getUserId() { return userId; }
    public Role getRole() { return role; }

    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return passwordHash; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
