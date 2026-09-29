package com.zippay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
    // toString: SignupRequest와 같은 방식으로 password 가리기
    @Override
    public String toString() {
        return "LoginRequest{email='" + email + "', password='****'}";
    }
}