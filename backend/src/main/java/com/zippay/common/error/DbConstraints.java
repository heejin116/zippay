package com.zippay.common.error;

import org.hibernate.exception.ConstraintViolationException;

/** DataIntegrityViolationException이 어떤 DB 제약 때문에 났는지 판별한다. */
public final class DbConstraints {

    private DbConstraints() {}

    /** 예외 원인 사슬에서 Hibernate ConstraintViolationException을 찾아 제약 이름을 비교한다. */
    public static boolean isViolationOf(Throwable e, String constraintName) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
