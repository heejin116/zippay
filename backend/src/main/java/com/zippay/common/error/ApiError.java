package com.zippay.common.error;

public record ApiError(int status, String code, String message) {

}
