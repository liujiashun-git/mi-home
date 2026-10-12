package com.mi.home.know.engine.auth.handler;

import cn.dev33.satoken.exception.NotLoginException;
import com.mi.home.know.engine.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 鉴权异常处理。
 * <p>
 * 未登录时 Sa-Token 会抛 {@link NotLoginException}。此前没有全局处理器，
 * 异常会穿透到 Servlet 容器，最终由 Tomcat 的 /error 兜底；而 /error 本身还要过一遍
 * 登录校验，于是前端拿到的是 <b>500 + HTML 错误页</b>，既不是 401 也不是 JSON，
 * 导致「登录过期自动跳登录页」这类前端逻辑失效。
 * <p>
 * 这里统一转成 401 + 标准 JSON 返回体。
 * 只处理鉴权异常，其余异常仍走框架默认行为，不改动其它错误语义。
 */
@Slf4j
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleNotLogin(NotLoginException e) {
        log.debug("未登录访问受保护资源: {}", e.getMessage());
        return R.fail(HttpStatus.UNAUTHORIZED.value(), "未登录或登录已过期");
    }
}
