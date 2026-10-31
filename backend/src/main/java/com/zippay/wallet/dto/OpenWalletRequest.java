package com.zippay.wallet.dto;

import jakarta.validation.constraints.NotBlank;

public record OpenWalletRequest(
        @NotBlank(message = "통화 코드는 필수 입력 항목입니다.")
        String currency
) {}
