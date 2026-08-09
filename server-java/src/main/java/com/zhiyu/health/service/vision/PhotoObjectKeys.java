package com.zhiyu.health.service.vision;

import java.util.regex.Pattern;

/**
 * MinIO object key 格式校验（ADR-0023）。
 *
 * <p>合法 key 形如 {@code photos/<yyyy-MM-dd>/<uuid>.jpg}，前缀固定为 {@code photos/}，
 * 路径片段只允许字母、数字、短横线与点，禁止 {@code ..}/{@code //} 等路径穿越片段。
 * 医生头像写入 {@code doctors.photo_url} 前需通过此校验，避免任意 URL 入库。
 */
public final class PhotoObjectKeys {

    // 形如 photos/2026-08-07/abc123-.jpg；前缀固定，日期段 + 安全字符的文件名 + 图片扩展名
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^photos/[0-9]{4}-[0-9]{2}-[0-9]{2}/[A-Za-z0-9_.-]+\\.(jpg|jpeg|png)$");

    private PhotoObjectKeys() {}

    /** 是否为合法的 MinIO object key（非空且匹配 {@link #KEY_PATTERN}）。 */
    public static boolean isValid(String objectKey) {
        return objectKey != null
                && !objectKey.isBlank()
                && KEY_PATTERN.matcher(objectKey).matches();
    }
}
