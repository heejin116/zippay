package com.zippay.wallet;

import com.jayway.jsonpath.JsonPath;
import com.zippay.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static com.zippay.common.security.CookieBearerTokenResolver.ACCESS_TOKEN_COOKIE;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional   // AuthFlowTest와 같은 이유로 테스트마다 롤백
class WalletApiTest {

    @Autowired MockMvc mockMvc;

    @Test
    void open_wallet_returns_201_with_location_and_zero_balance() throws Exception {
        Cookie alice = signupAndLogin("alice@zippay.com");

        open(alice, "KRW")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/wallets/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.balance").value("0"));   // 문자열, 통화 자릿수로 표시
    }

    @Test
    void usd_balance_is_shown_with_two_decimal_places() throws Exception {
        Cookie alice = signupAndLogin("alice@zippay.com");

        open(alice, "USD")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value("0.00"));
    }

    @Test
    void opening_same_currency_twice_returns_409() throws Exception {
        Cookie alice = signupAndLogin("alice@zippay.com");
        open(alice, "KRW").andExpect(status().isCreated());

        open(alice, "KRW")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_WALLET"));
    }

    @Test
    void unsupported_currency_returns_400() throws Exception {
        Cookie alice = signupAndLogin("alice@zippay.com");

        open(alice, "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void blank_currency_returns_400() throws Exception {
        Cookie alice = signupAndLogin("alice@zippay.com");

        mockMvc.perform(post("/api/wallets").cookie(alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void list_returns_only_own_wallets() throws Exception {
        Cookie alice = signupAndLogin("alice@zippay.com");
        Cookie bob = signupAndLogin("bob@zippay.com");
        open(alice, "KRW");
        open(alice, "USD");
        open(bob, "KRW");

        mockMvc.perform(get("/api/wallets").cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].currency").value("KRW"))
                .andExpect(jsonPath("$[1].currency").value("USD"));
    }

    @Test
    void get_own_wallet_returns_200_but_other_users_wallet_returns_404() throws Exception {
        Cookie alice = signupAndLogin("alice@zippay.com");
        Cookie bob = signupAndLogin("bob@zippay.com");
        String body = open(alice, "KRW").andReturn().getResponse().getContentAsString();
        Number walletId = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/wallets/{id}", walletId).cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("KRW"));

        // 남의 지갑은 403이 아니라 404 — 존재 여부를 노출하지 않는다
        mockMvc.perform(get("/api/wallets/{id}", walletId).cookie(bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void wallets_require_authentication() throws Exception {
        mockMvc.perform(get("/api/wallets")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/wallets")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"KRW\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions open(Cookie cookie, String currency) throws Exception {
        return mockMvc.perform(post("/api/wallets").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"" + currency + "\"}"));
    }

    private Cookie signupAndLogin(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123!\"}";
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie(ACCESS_TOKEN_COOKIE);
    }
}
