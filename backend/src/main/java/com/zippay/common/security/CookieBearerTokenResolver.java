package com.zippay.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.WebUtils;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

public class CookieBearerTokenResolver implements BearerTokenResolver {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";

    @Override
    public String resolve(HttpServletRequest request) {
        Cookie cookie =  WebUtils.getCookie(request, ACCESS_TOKEN_COOKIE);

        return cookie == null ? null : cookie.getValue();
    }
}
