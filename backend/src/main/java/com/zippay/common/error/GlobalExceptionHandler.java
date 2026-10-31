package com.zippay.common.error;

import com.zippay.auth.exception.DuplicateEmailException;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.common.money.UnsupportedCurrencyException;
import com.zippay.wallet.exception.DuplicateWalletException;
import com.zippay.wallet.exception.WalletNotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    // 401: 로그인 실패
    @ExceptionHandler(AuthenticationException.class)   // org.springframework.security.core
    public ResponseEntity<ApiError> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(401,"INVALID_INPUT", "이메일 또는 비밀번호가 올바르지 않습니다."));   // 401 UNAUTHORIZED, "INVALID_CREDENTIALS", 고정 메시지
    }
}
