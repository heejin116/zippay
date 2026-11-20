# ZipPay API

구현된 API와 규칙. 개요는 [README](../README.md), 설계 배경은 [ARCHITECTURE.md](ARCHITECTURE.md).

## 엔드포인트 (구현된 것)
| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/signup` · `/api/auth/login` · `/api/auth/logout` | 회원가입 · 로그인(HttpOnly 쿠키 JWT) · 로그아웃 |
| GET | `/api/users/me` | 내 정보 |
| POST / GET | `/api/wallets` | 지갑 개설(통화당 1개) / 내 지갑 목록 |
| GET | `/api/wallets/{walletId}` | 지갑 조회 (남의 지갑은 404) |
| POST | `/api/wallets/{walletId}/charges` | 충전. `Idempotency-Key`(UUID) 헤더 필수, 본문 `{"amount": "10000"}` |

## 충전 규칙
- 통화는 지갑에서 정해지므로 본문에 받지 않는다. 금액은 통화 소수 자릿수(KRW·JPY 0, USD 2)를 넘으면 400.
- 외부(은행) 입금은 **즉시 성공으로 가정**한다. 은행 연동·실패·지연은 범위 밖이라 원장에 status가 없다(커밋된 거래 = 완료된 거래).
- 한도(정책 가정값): 1회 충전 한도 KRW 1,000,000 · USD 1,000.00 · JPY 100,000 (초과 시 400 `CHARGE_LIMIT_EXCEEDED`),
  지갑 보유 한도 KRW 2,000,000 · USD 2,000.00 · JPY 200,000 (초과 시 422 `BALANCE_LIMIT_EXCEEDED`, 송금 입금에도 적용).
  정책 값이라 DB CHECK가 아니라 도메인에서만 검사한다.
- 응답: `201 {transactionId, walletId, currency, amount, balanceAfter, createdAt}`. `balanceAfter`는 그 거래 직후 잔액.

## 멱등성
| 상황 | 응답 |
|---|---|
| 헤더 없음 / UUID 아님 | 400 `IDEMPOTENCY_KEY_REQUIRED` / `INVALID_IDEMPOTENCY_KEY` |
| 처음 보는 키 | 처리 후 201 |
| 같은 키 · 같은 요청 | 원래 결과 그대로 201 + `Idempotent-Replayed: true` (잔액은 한 번만 반영) |
| 같은 키 · 다른 요청 (금액·지갑·거래 유형이 다름) | 422 `IDEMPOTENCY_KEY_REUSED` |

- 키는 사용자별로 유일하다: UNIQUE(user_id, idempotency_key). 대소문자만 다른 키는 같은 키로 본다.
- "같은 요청"은 원본 JSON 바이트가 아니라 정규화한 값(`type=CHARGE&walletId=..&currency=..&amount=..`)의 SHA-256으로 판정한다. 공백·필드 순서·`10000`/`10000.00` 차이는 같은 요청이다.
