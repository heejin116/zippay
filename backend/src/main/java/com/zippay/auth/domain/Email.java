package com.zippay.auth.domain;

import java.util.Locale;

public record Email(String value) {

    private static final int MAX_LENGTH = 254;

    public Email {
        // 1) null → 예외
        if (value == null) {
            throw new IllegalArgumentException("이메일을 입력해주세요");
        }

        // 2) 정규화: 앞뒤 공백 제거 → 소문자 (재할당)
        value = value.strip().toLowerCase(Locale.ROOT);

        // 3) 길이 검증
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("이메일이 너무 길어요");
        }

        // 4) @ 위치 검증
        int at = value.indexOf('@');
        if ( at <= 0
                || at == value.length()-1
                || at != value.lastIndexOf('@') ) {
            throw new IllegalArgumentException("올바르지 않은 이메일 형식입니다.");
        }
    }
}
