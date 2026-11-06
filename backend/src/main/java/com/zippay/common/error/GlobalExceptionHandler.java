package com.zippay.common.error;

import com.zippay.auth.exception.DuplicateEmailException;
import com.zippay.charge.exception.ChargeLimitExceededException;
import com.zippay.common.idempotency.IdempotencyKey;
import com.zippay.common.idempotency.IdempotencyKeyReusedException;
import com.zippay.common.idempotency.InvalidIdempotencyKeyException;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.common.money.UnsupportedCurrencyException;
import com.zippay.ledger.exception.InvalidLedgerTransactionException;
import com.zippay.wallet.exception.BalanceLimitExceededException;
import com.zippay.wallet.exception.DuplicateWalletException;
import com.zippay.wallet.exception.InsufficientBalanceException;
import com.zippay.wallet.exception.WalletNotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 422는 HttpStatus 상수 이름이 Spring 버전마다 달라(UNPROCESSABLE_ENTITY → UNPROCESSABLE_CONTENT) 숫자로 쓴다
    private static final int UNPROCESSABLE = 422;

    // 409: 이메일 중복 (순차 요청)
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(DuplicateEmailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(409, "DUPLICATE_EMAIL", e.getMessage()));
    }

    // 409 or 500: DB 제약 위반 (동시 요청)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException e) {
        if (e.getCause() instanceof ConstraintViolationException cve     // org.hibernate.exception
                && "uk_users_email".equals(cve.getConstraintName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(409, "DUPLICATE_EMAIL", "이미 가입된 이메일입니다."));
        }
        if (e.getCause() instanceof ConstraintViolationException cve
                && "uk_wallets_user_currency".equals(cve.getConstraintName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(409, "DUPLICATE_WALLET", "이미 같은 통화의 지갑이 있습니다."));
        }
        throw e;
    }

    // 409: 같은 통화 지갑 중복 개설 (순차 요청)
    @ExceptionHandler(DuplicateWalletException.class)
    public ResponseEntity<ApiError> handleDuplicateWallet(DuplicateWalletException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(409, "DUPLICATE_WALLET", e.getMessage()));
    }

    // 404: 없는 지갑 + 남의 지갑 (존재 여부 비노출을 위해 403을 쓰지 않는다)
    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ApiError> handleWalletNotFound(WalletNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(404, "WALLET_NOT_FOUND", e.getMessage()));
    }

    // 400: 지원하지 않는 통화
    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ResponseEntity<ApiError> handleUnsupportedCurrency(UnsupportedCurrencyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(400, "UNSUPPORTED_CURRENCY", e.getMessage()));
    }

    // 400: 0 이하 금액, 통화 소수 자릿수 초과
    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ApiError> handleInvalidAmount(InvalidAmountException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(400, "INVALID_AMOUNT", e.getMessage()));
    }

    // 400: @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(400, "INVALID_INPUT", message));
    }

    // 401: 로그인 실패. 이메일이 없는지 비밀번호가 틀렸는지 구분하지 않는 고정 메시지 (계정 존재 여부 비노출)
    // INVALID_INPUT(400, @Valid 실패)과 code를 분리해 클라이언트가 두 경우를 구분할 수 있게 한다
    @ExceptionHandler(AuthenticationException.class)   // org.springframework.security.core
    public ResponseEntity<ApiError> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    // ---------- 멱등성 (STEP 5) ----------
    // 아래 400들은 전부 클라이언트가 고칠 수 있는 오류다. 5xx로 내면 서버 장애 지표(STEP 10~12 알림)를 오염시키고,
    // 5xx를 "재시도 가능"으로 보는 클라이언트·게이트웨이가 고쳐지지 않는 요청을 계속 재전송한다.

    // 400: 필수 헤더 누락. Idempotency-Key면 전용 code로 구분한다
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        if (IdempotencyKey.HEADER.equalsIgnoreCase(e.getHeaderName())) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(400, "IDEMPOTENCY_KEY_REQUIRED", IdempotencyKey.HEADER + " 헤더가 필요합니다."));
        }
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "MISSING_HEADER", e.getHeaderName() + " 헤더가 필요합니다."));
    }

    // 400: Idempotency-Key 형식 오류 (UUID 아님)
    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ApiError> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException e) {
        return ResponseEntity.badRequest().body(new ApiError(400, "INVALID_IDEMPOTENCY_KEY", e.getMessage()));
    }

    // 422: 같은 키·다른 본문
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ApiError> handleIdempotencyKeyReused(IdempotencyKeyReusedException e) {
        return ResponseEntity.status(UNPROCESSABLE).body(new ApiError(UNPROCESSABLE, "IDEMPOTENCY_KEY_REUSED", e.getMessage()));
    }

    // ---------- 잔액·한도 ----------

    // 400: 1회 충전 한도 초과 — 요청 값만으로 판정
    @ExceptionHandler(ChargeLimitExceededException.class)
    public ResponseEntity<ApiError> handleChargeLimit(ChargeLimitExceededException e) {
        return ResponseEntity.badRequest().body(new ApiError(400, "CHARGE_LIMIT_EXCEEDED", e.getMessage()));
    }

    // 422: 보유 한도 초과 — 요청은 올바르지만 현재 잔액 상태 때문에 처리 불가
    @ExceptionHandler(BalanceLimitExceededException.class)
    public ResponseEntity<ApiError> handleBalanceLimit(BalanceLimitExceededException e) {
        return ResponseEntity.status(UNPROCESSABLE).body(new ApiError(UNPROCESSABLE, "BALANCE_LIMIT_EXCEEDED", e.getMessage()));
    }

    // 422: 잔액 부족 (STEP 6 송금에서 사용) — 보유 한도와 같은 이유로 422
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiError> handleInsufficientBalance(InsufficientBalanceException e) {
        return ResponseEntity.status(UNPROCESSABLE).body(new ApiError(UNPROCESSABLE, "INSUFFICIENT_BALANCE", e.getMessage()));
    }

    // ---------- 기타 ----------

    // 400: 본문 JSON 파싱 실패 (예: amount가 "abc")
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError(400, "INVALID_INPUT", "요청 본문을 읽을 수 없습니다."));
    }

    // 500: 원장 구조 위반은 입력 오류가 아니라 서버 로직 버그다. 내부 메시지는 응답에 싣지 않고 로그로만 남긴다
    @ExceptionHandler(InvalidLedgerTransactionException.class)
    public ResponseEntity<ApiError> handleInvalidLedger(InvalidLedgerTransactionException e) {
        log.error("원장 불변식 위반 (서버 버그)", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(500, "INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }
}
