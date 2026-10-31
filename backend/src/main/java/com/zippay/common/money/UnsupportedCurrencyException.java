package com.zippay.common.money;

public class UnsupportedCurrencyException extends RuntimeException {
    public UnsupportedCurrencyException(String message) {
        super(message);
    }
}
