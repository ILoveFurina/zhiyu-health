package com.zhiyu.health.service;

/**
 * MinIO object key 的 C 端访问 URL 映射（ADR-0023 C 端消费约定，票 59）。
 *
 * <p>doctors.photo_url 与 image 消息 content 存的是 object key 而非 URL；C 端 API 出口
 * 统一把 photo_url 映射为 /api/c/photos 代理相对 URL（AuthFilter 放行、key 即凭证，
 * 见 {@link com.zhiyu.health.controller.c.PhotoController}），前端 &lt;image&gt; 直接可用。
 * 空 key 返回空串，前端据此走姓氏文字圆降级；与 B 端票 54 的 /api/b/photos 先例同构，
 * 前端不感知 object key。
 */
public final class PhotoUrls {

    private static final String C_ENDPOINT = "/api/c/photos?key=";

    private PhotoUrls() {}

    public static String cUrl(String objectKey) {
        return objectKey == null || objectKey.isBlank() ? "" : C_ENDPOINT + objectKey;
    }
}
