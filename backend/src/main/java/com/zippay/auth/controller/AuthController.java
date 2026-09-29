package com.zippay.auth.controller;

import com.zippay.auth.dto.SignupRequest;
import com.zippay.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        Long userId = authService.signup(request);
        return ResponseEntity
                .created(URI.create("/api/users/" + userId))
                .build();

    }
}
