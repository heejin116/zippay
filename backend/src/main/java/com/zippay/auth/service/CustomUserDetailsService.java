package com.zippay.auth.service;

import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import com.zippay.auth.repository.UserRepository;
import com.zippay.common.security.CustomUserDetails;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        Email email;
        try {
            email = new Email(username);
        } catch (IllegalArgumentException e) {
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다.");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다"));

        return new CustomUserDetails(user);
    }
}