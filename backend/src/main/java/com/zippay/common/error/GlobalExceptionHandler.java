package com.zippay.common.error;

import com.zippay.auth.exception.DuplicateEmailException;
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
        throw e;
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
