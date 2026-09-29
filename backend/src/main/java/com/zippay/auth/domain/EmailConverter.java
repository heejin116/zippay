package com.zippay.auth.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EmailConverter implements AttributeConverter<Email, String> {

    // 자바 객체 -> DB 문자열 변환 (저장할 때)
    @Override
    public String convertToDatabaseColumn(Email attribute) {
        return attribute == null ? null : attribute.value();
    }

    // DB 문자열 -> 자바 객체 변환 (조회할 때)
    @Override
    public Email convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new Email(dbData);
    }
}
