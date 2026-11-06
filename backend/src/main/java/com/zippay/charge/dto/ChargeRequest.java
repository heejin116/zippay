package com.zippay.charge.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 통화는 받지 않는다 — 지갑이 (사용자, 통화)당 1개라 walletId에서 정해진다.
 * amount는 JSON 숫자·문자열 모두 BigDecimal로 받는다 (프론트는 정밀도 보존을 위해 문자열로 보낸다).
 */
public record ChargeRequest(@NotNull(message = "충전 금액은 필수입니다.") BigDecimal amount) {
}
