package com.zippay.auth;

import com.zippay.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static com.zippay.common.security.CookieBearerTokenResolver.ACCESS_TOKEN_COOKIE;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional   // MockMvc는 같은 스레드에서 돌기 때문에 테스트 끝나면 가입 데이터가 롤백된다
class AuthFlowTest {

    private static final String BODY = """
            {"email":"flow@zippay.com","password":"password123!"}
            """;

    @Autowired MockMvc mockMvc;
    @Autowired JwtEncoder jwtEncoder;

    @Test
    void me_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                // Spring Security 7은 RFC 9728에 따라 resource_metadata 파라미터를 붙인다.
                // 토큰이 "없는" 경우이므로 error 파라미터는 없어야 한다 (invalid_token과 구분)
                .andExpect(header().string("WWW-Authenticate",
                        allOf(startsWith("Bearer"), not(containsString("error=")))));
    }

    @Test
    void me_with_valid_cookie_returns_200() throws Exception {
        Cookie cookie = signupAndLogin();

        mockMvc.perform(get("/api/users/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void me_with_tampered_token_returns_401() throws Exception {
        String valid = signupAndLogin().getValue();

        // 서명부 가운데 한 글자를 바꿔 위조 토큰을 만든다
        // (마지막 글자는 base64url 패딩 비트라 바꿔도 디코딩 결과가 같을 수 있어 피한다)
        String[] parts = valid.split("\\.");
        char[] sig = parts[2].toCharArray();
        int mid = sig.length / 2;
        sig[mid] = sig[mid] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(sig);

        mockMvc.perform(get("/api/users/me").cookie(new Cookie(ACCESS_TOKEN_COOKIE, tampered)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    @Test
    void me_with_expired_token_returns_401() throws Exception {
        // 서명은 유효하지만 만료된 토큰. 기본 clock skew(60초)보다 충분히 과거로 잡는다
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        String expired = encode(past, past.plus(Duration.ofMinutes(5)));

        mockMvc.perform(get("/api/users/me").cookie(new Cookie(ACCESS_TOKEN_COOKIE, expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    @Test
    void login_succeeds_even_with_expired_cookie() throws Exception {
        // /api/auth/** 는 publicChain이라 토큰 검증을 하지 않는다 → 만료 쿠키가 남아 있어도 재로그인 가능해야 함
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        String expired = encode(past, past.plus(Duration.ofMinutes(5)));

        mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie(ACCESS_TOKEN_COOKIE, expired))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void login_with_wrong_password_returns_401_invalid_credentials() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"flow@zippay.com","password":"wrong-password1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void logout_expires_access_token_cookie() throws Exception {
        Cookie loginCookie = signupAndLogin();

        // 삭제 쿠키는 발급 쿠키와 이름·Path가 같아야 브라우저가 지운다
        mockMvc.perform(post("/api/auth/logout").cookie(loginCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value(ACCESS_TOKEN_COOKIE, ""))
                .andExpect(cookie().maxAge(ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().path(ACCESS_TOKEN_COOKIE, "/"))
                .andExpect(cookie().httpOnly(ACCESS_TOKEN_COOKIE, true));
    }

    @Test
    void logout_without_cookie_returns_204() throws Exception {
        // 이미 만료됐거나 로그인 안 한 상태에서 눌러도 실패하지 않아야 한다 (publicChain)
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }

    private Cookie signupAndLogin() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent())
                .andReturn();

        return result.getResponse().getCookie(ACCESS_TOKEN_COOKIE);  // Set-Cookie 헤더를 파싱해줌
    }

    private String encode(Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("zippay")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject("1")
                .claim("role", "USER")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
