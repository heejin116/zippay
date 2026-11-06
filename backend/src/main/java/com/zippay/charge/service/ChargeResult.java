package com.zippay.charge.service;

import com.zippay.charge.dto.ChargeResponse;

/** @param replayed true면 이미 처리된 요청의 원래 결과 (응답에 Idempotent-Replayed: true) */
public record ChargeResult(ChargeResponse response, boolean replayed) {
}
