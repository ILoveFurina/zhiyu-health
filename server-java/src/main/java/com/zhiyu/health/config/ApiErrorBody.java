package com.zhiyu.health.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * 错误体唯一生产者：所有出口（servlet 过滤器直写 / advice 序列化）的 {"detail": ...} 形状在此定型，
 * 与票 02 Python 原件的错误契约一致。过滤器由 WebConfig 手工装配、不经 Jackson，因此提供手写 JSON 出口。
 */
public final class ApiErrorBody {

    private ApiErrorBody() {}

    /** advice 出口：交给 Spring/Jackson 序列化的不可变体 */
    public static Map<String, Object> of(String detail) {
        return Map.of("detail", detail);
    }

    /** 带业务错误码的 advice 出口：detail 内嵌 {code, message} */
    public static Map<String, Object> of(String code, String message) {
        return Map.of("detail", Map.of("code", code, "message", message));
    }

    /** 过滤器出口：绕过 MVC 直接写回，必须与 Jackson 输出同形 */
    public static void write(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\": \"" + escapeJson(detail) + "\"}");
    }

    /** 最小 JSON 字符串转义：文案当前都是常量中文，仍防御引号、反斜杠与控制字符 */
    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
