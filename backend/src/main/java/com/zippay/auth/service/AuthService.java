package com.zippay.auth.service;

import com.zippay.auth.dto.LoginRequest;
import com.zippay.auth.exception.DuplicateEmailException;
import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import com.zippay.auth.dto.SignupRequest;
import com.zippay.auth.repository.UserRepository;
import com.zippay.common.security.CustomUserDetails;
import com.zippay.common.security.JwtProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtProvider jwtProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtProvider = jwtProvider;
    }

    @Transactional
    public Long signup(SignupRequest request) {
        Email email = new Email(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException("이미 가입된 이메일입니다.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.create(email, passwordHash);
        User saved = userRepository.save(user);

        return saved.getId();
    }
    public String login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));

        CustomUserDetails principal = (CustomUserDetails) auth.getPrincipal();
        return jwtProvider.issueAccessToken(principal.getUserId(), principal.getRole());
    }
}
