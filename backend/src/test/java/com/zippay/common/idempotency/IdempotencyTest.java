package com.zippay.common.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyTest {

    @Test
    void uuid_key_is_normalized_to_lowercase() {
        assertThat(IdempotencyKey.normalize("3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
                .isEqualTo("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    }

    @Test
    void non_uuid_key_is_rejected() {
        for (String bad : new String[]{null, "", " ", "abc", "3f2504e0-4f89-11d3-9a0c-0305e82c3301 ", "x".repeat(36)}) {
            assertThatThrownBy(() -> IdempotencyKey.normalize(bad)).isInstanceOf(InvalidIdempotencyKeyException.class);
        }
    }

    @Test
    void request_hash_is_lowercase_sha256_hex_and_deterministic() {
        String hash = RequestHash.sha256Hex("type=CHARGE&walletId=1&currency=KRW&amount=10000");

        assertThat(hash).matches("^[0-9a-f]{64}$")   // V3 ck_ledger_tx_request_hash 형식
                .isEqualTo(RequestHash.sha256Hex("type=CHARGE&walletId=1&currency=KRW&amount=10000"))
                .isNotEqualTo(RequestHash.sha256Hex("type=TRANSFER&walletId=1&currency=KRW&amount=10000"));
        // SHA-256("abc") 표준 테스트 벡터
        assertThat(RequestHash.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
