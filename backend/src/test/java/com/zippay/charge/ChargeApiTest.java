package com.zippay.charge;

import com.jayway.jsonpath.JsonPath;
import com.zippay.TestcontainersConfiguration;
import com.zippay.ledger.repository.LedgerReconciliationRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.zippay.common.security.CookieBearerTokenResolver.ACCESS_TOKEN_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 충전 API. WalletApiTest와 달리 @Transactional을 걸지 않는다 — 충전 서비스는 트랜잭션 밖에서
 * 선조회·재조회를 하도록 설계돼 있어, 테스트 트랜잭션으로 감싸면 실제와 다른 경로를 검증하게 된다.
 * 대신 테스트마다 고유 이메일을 쓴다. 커밋된 데이터는 전부 원장을 거친 정상 데이터라 대사 쿼리도 통과해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChargeApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired LedgerReconciliationRepository reconciliation;

    // ---------- 정상 ----------

    @Test
    void charge_returns_201_with_balance_after_and_updates_wallet() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");

        charge(alice, wallet, key(), "{\"amount\":10000}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.transactionId").isNumber())
                .andExpect(jsonPath("$.walletId").value(wallet))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.amount").value("10000"))
                .andExpect(jsonPath("$.balanceAfter").value("10000"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertBalance(alice, wallet, "10000");
        assertReconciled();
    }

    @Test
    void usd_charge_is_shown_with_two_decimal_places() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "USD");

        charge(alice, wallet, key(), "{\"amount\":\"12.5\"}")   // 문자열 금액도 받는다 (프론트가 이렇게 보냄)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("12.50"))
                .andExpect(jsonPath("$.balanceAfter").value("12.50"));
    }

    // ---------- 멱등성 ----------

    @Test
    void replay_with_same_key_returns_original_result_and_charges_once() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");
        String key = key();

        String first = charge(alice, wallet, key, "{\"amount\":10000}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 표기만 다른 같은 금액(10000.00), 대문자 키 → 같은 요청으로 판정
        String second = charge(alice, wallet, key.toUpperCase(), "{ \"amount\" : 10000.00 }")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertBalance(alice, wallet, "10000");
    }

    @Test
    void replay_returns_balance_after_of_original_transaction_not_current_balance() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");
        String key = key();
        charge(alice, wallet, key, "{\"amount\":10000}").andExpect(status().isCreated());
        charge(alice, wallet, key(), "{\"amount\":5000}").andExpect(status().isCreated());

        charge(alice, wallet, key, "{\"amount\":10000}")
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.balanceAfter").value("10000"));   // 현재 잔액은 15000
        assertBalance(alice, wallet, "15000");
    }

    @Test
    void same_key_with_different_amount_returns_422_and_does_not_charge() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");
        String key = key();
        charge(alice, wallet, key, "{\"amount\":10000}").andExpect(status().isCreated());

        charge(alice, wallet, key, "{\"amount\":20000}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertBalance(alice, wallet, "10000");
    }

    @Test
    void same_key_on_another_wallet_returns_422() throws Exception {
        Cookie alice = signupAndLogin();
        long krw = openWallet(alice, "KRW");
        long usd = openWallet(alice, "USD");
        String key = key();
        charge(alice, krw, key, "{\"amount\":100}").andExpect(status().isCreated());

        charge(alice, usd, key, "{\"amount\":100}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertBalance(alice, usd, "0.00");
    }

    @Test
    void same_key_is_independent_per_user() throws Exception {
        // UNIQUE는 (user_id, idempotency_key) — 다른 사용자가 우연히 같은 키를 써도 각자 처리된다
        Cookie alice = signupAndLogin();
        Cookie bob = signupAndLogin();
        long aliceWallet = openWallet(alice, "KRW");
        long bobWallet = openWallet(bob, "KRW");
        String key = key();

        charge(alice, aliceWallet, key, "{\"amount\":1000}").andExpect(status().isCreated());
        charge(bob, bobWallet, key, "{\"amount\":1000}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void missing_idempotency_key_returns_400() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");

        mockMvc.perform(post("/api/wallets/{id}/charges", wallet).cookie(alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertBalance(alice, wallet, "0");
    }

    @Test
    void malformed_idempotency_key_returns_400() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");

        charge(alice, wallet, "not-a-uuid", "{\"amount\":10000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    // ---------- 입력·한도 ----------

    @Test
    void invalid_amounts_return_400() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");

        charge(alice, wallet, key(), "{\"amount\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        charge(alice, wallet, key(), "{\"amount\":-1000}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        charge(alice, wallet, key(), "{\"amount\":100.5}")     // KRW 소수 금지
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        charge(alice, wallet, key(), "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        charge(alice, wallet, key(), "{\"amount\":\"abc\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertBalance(alice, wallet, "0");
    }

    @Test
    void charge_over_per_charge_limit_returns_400() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");

        charge(alice, wallet, key(), "{\"amount\":1000000}").andExpect(status().isCreated());   // 한도 정확히 = 허용
        charge(alice, wallet, key(), "{\"amount\":1000001}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGE_LIMIT_EXCEEDED"));
        assertBalance(alice, wallet, "1000000");
    }

    @Test
    void charge_over_balance_limit_returns_422_and_retry_with_same_key_runs_again() throws Exception {
        Cookie alice = signupAndLogin();
        long wallet = openWallet(alice, "KRW");
        charge(alice, wallet, key(), "{\"amount\":1000000}").andExpect(status().isCreated());
        charge(alice, wallet, key(), "{\"amount\":1000000}").andExpect(status().isCreated());   // 보유 한도 200만 도달
        String key = key();

        charge(alice, wallet, key, "{\"amount\":1}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("BALANCE_LIMIT_EXCEEDED"));
        // 실패는 롤백되어 키가 남지 않는다 → 같은 키 재시도는 재생이 아니라 재실행 (그래서 또 422)
        charge(alice, wallet, key, "{\"amount\":1}")
                .andExpect(status().is(422))
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertBalance(alice, wallet, "2000000");
        assertReconciled();
    }

    // ---------- 권한 ----------

    @Test
    void charging_other_users_wallet_returns_404() throws Exception {
        Cookie alice = signupAndLogin();
        Cookie bob = signupAndLogin();
        long aliceWallet = openWallet(alice, "KRW");

        charge(bob, aliceWallet, key(), "{\"amount\":10000}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
        assertBalance(alice, aliceWallet, "0");
    }

    @Test
    void charge_requires_authentication() throws Exception {
        mockMvc.perform(post("/api/wallets/{id}/charges", 1)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 헬퍼 ----------

    private ResultActions charge(Cookie cookie, long walletId, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/wallets/{id}/charges", walletId).cookie(cookie)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void assertBalance(Cookie cookie, long walletId, String expected) throws Exception {
        mockMvc.perform(get("/api/wallets/{id}", walletId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(expected));
    }

    private void assertReconciled() {
        assertThat(reconciliation.findUnbalancedTransactionIds()).isEmpty();
        assertThat(reconciliation.findWalletIdsWithBalanceMismatch()).isEmpty();
        assertThat(reconciliation.findCurrenciesWithTotalMismatch()).isEmpty();
    }

    private long openWallet(Cookie cookie, String currency) throws Exception {
        String body = mockMvc.perform(post("/api/wallets").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private Cookie signupAndLogin() throws Exception {
        String email = "charge-" + UUID.randomUUID() + "@zippay.com";
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123!\"}";
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie(ACCESS_TOKEN_COOKIE);
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
