package com.zhiyu.health.controller.c;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.service.MinioStorageService;
import com.zhiyu.health.service.MinioStorageService.PhotoContent;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * C 端图片代理端点（ADR-0023 回拉链路）：历史会话中的 image 消息按 object_key 经此端点
 * 回拉 MinIO 原图。支付宝小程序 &lt;image src&gt; 组件请求图片时不带 Authorization header，
 * 故此端点在 AuthFilter 放行，以 object_key 的 UUID 不可猜测性作为取图凭证（demo 场景）。
 */
@RestController
@RequestMapping("/api/c/photos")
@RequiredArgsConstructor
@Slf4j
public class PhotoController {

    private final MinioStorageService minioStorage;

    /**
     * 按 object_key 回拉原图并流式透传给前端。
     *
     * <p>key 即凭证：object_key 形如 photos/yyyy-MM-dd/&lt;uuid&gt;.jpg，UUID 不可枚举。
     * 生产环境应改为短期签名 token，避免 URL 转发导致的越权访问。
     */
    @GetMapping
    public ResponseEntity<StreamingResponseBody> getPhoto(@RequestParam("key") String objectKey) {
        if (objectKey == null || objectKey.isBlank() || !objectKey.startsWith("photos/")) {
            throw new ApiException(404, "图片不存在");
        }
        // 取流与读取分离：MinIO 不可用/对象不存在返回 404，引导前端走无图兜底。
        PhotoContent content = minioStorage.getObject(objectKey).orElseThrow(() -> new ApiException(404, "图片不存在"));
        StreamingResponseBody body = outputStream -> {
            // try-with-resources 确保无论透传成功与否都关闭 MinIO 输入流，避免连接泄漏。
            try (InputStream input = content.stream()) {
                input.transferTo(outputStream);
            } catch (IOException e) {
                // 客户端断开等写入异常不可恢复，仅记日志不抛出（已开始写响应体，无法再改状态码）。
                log.warn("图片透传中断：{}", e.getMessage());
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .body(body);
    }
}
