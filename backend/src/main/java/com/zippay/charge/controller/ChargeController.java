package com.zippay.charge.controller;

import com.zippay.charge.dto.ChargeRequest;
import com.zippay.charge.dto.ChargeResponse;
import com.zippay.charge.service.ChargeResult;
import com.zippay.charge.service.ChargeService;
import com.zippay.common.idempotency.IdempotencyKey;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallets/{walletId}/charges")
public class ChargeController {

    private final ChargeService chargeService;

    public ChargeController(ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    /**
     * 충전. Idempotency-Key 헤더 필수 — 없으면 Spring이 MissingRequestHeaderException을 던지고 400으로 매핑된다.
     * 재요청은 원래 결과와 같은 201 + Idempotent-Replayed: true.
     * (단건 조회 API가 아직 없어 Location 헤더는 붙이지 않는다)
     */
    @PostMapping
    public ResponseEntity<ChargeResponse> charge(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable Long walletId,
                                                 @RequestHeader(IdempotencyKey.HEADER) String idempotencyKey,
                                                 @Valid @RequestBody ChargeRequest request) {
        ChargeResult result = chargeService.charge(
                Long.valueOf(jwt.getSubject()), walletId, idempotencyKey, request.amount());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(IdempotencyKey.REPLAYED_HEADER, "true");
        }
        return response.body(result.response());
    }
}
