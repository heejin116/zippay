package com.zippay.auth.dto;

import jakarta.validation.constraints.*;

public record SignupRequest(
        @NotBlank(message = "이메일은 필수 입력 항목입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수 입력 항목입니다.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하로 입력해주세요.")
        @Pattern(
                regexp = "^[\\x21-\\x7E]*$",   // 공백(0x20) 제외, !(0x21) ~ ~(0x7E)까지 출력 가능한 ASCII
                message = "비밀번호는 공백 없이 영문, 숫자, 특수문자만 입력 가능합니다."
        )
        String password
) {
    @Override
    public String toString() {
        return "SignupRequest{email='" + email + "', password='****'}";
    }
}