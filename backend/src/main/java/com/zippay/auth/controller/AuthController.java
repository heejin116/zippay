package com.zippay.auth.controller;

import com.zippay.auth.dto.LoginRequest;
import com.zippay.auth.dto.SignupRequest;
import com.zippay.auth.service.AuthService;
import com.zippay.common.security.JwtProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;

import static com.zippay.common.security.CookieBearerTokenResolver.ACCESS_TOKEN_COOKIE;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        Long userId = authService.signup(request);
        return ResponseEntity
                .created(URI.create("/api/users/" + userId))
                .build();

    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request);


        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, accessTokenCookie(token, jwtProperties.accessTokenTtl()).toString())
                .build();
    }

    /**
     * 같은 이름·Path의 쿠키를 빈 값 + Max-Age=0으로 덮어써 브라우저가 지우게 한다.
     * HttpOnly 쿠키는 JS로 지울 수 없으므로 서버가 지워줘야 한다.
     * publicChain(/api/auth/**)이라 토큰이 없거나 만료돼도 호출할 수 있다 → 항상 204.
     * 주의: 쿠키만 지울 뿐 JWT 자체는 만료(access-token-ttl)까지 유효하다. stateless라 서버에 폐기 목록이 없기 때문.
     *       토큰 폐기·재사용 감지는 STEP 9(refresh 토큰 회전)에서 다룬다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, accessTokenCookie("", Duration.ZERO).toString())
                .build();
    }

    // 발급·삭제 쿠키의 이름·Path·Domain이 하나라도 다르면 브라우저는 다른 쿠키로 보고 지우지 않는다 → 한 곳에서 만든다
    private static ResponseCookie accessTokenCookie(String value, Duration maxAge) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
