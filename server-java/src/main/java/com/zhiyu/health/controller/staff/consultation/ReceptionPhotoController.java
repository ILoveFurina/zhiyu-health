package com.zhiyu.health.controller.staff.consultation;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.common.MinioStorageService.PhotoContent;
import com.zhiyu.health.service.vision.PhotoObjectKeys;
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
 * 接诊台图片代理（问诊图片回看修复）：挂在 reception 命名空间下（AdminInterceptor 放行、
 * AuthFilter 仍要求 staff JWT），doctor 角色可直接访问，而票 54 的 /api/b/photos 仅限
 * admin 角色（AdminInterceptor 拦截），医生回看患者问诊图片会 403。
 *
 * <p>object_key 即取图凭证（UUID 不可猜测，与 C 端 PhotoController、B 端 /api/b/photos 同语义），
 * 不做会话归属校验；透传逻辑与 DoctorPhotoController.getPhoto 同构。
 */
@RestController
@RequestMapping("/api/b/reception/photos")
@RequiredArgsConstructor
@Slf4j
public class ReceptionPhotoController {

    private final MinioStorageService minioStorage;

    /** 按 object_key 回拉问诊图片并流式透传（鉴权后访问，bucket 不开公共读）。 */
    @GetMapping
    public ResponseEntity<StreamingResponseBody> getPhoto(@RequestParam("key") String objectKey) {
        if (!PhotoObjectKeys.isValid(objectKey)) {
            throw new ApiException(404, "图片不存在");
        }
        PhotoContent content = minioStorage.getObject(objectKey).orElseThrow(() -> new ApiException(404, "图片不存在"));
        StreamingResponseBody body = outputStream -> {
            // try-with-resources 确保无论透传成功与否都关闭 MinIO 输入流，避免连接泄漏。
            try (InputStream input = content.stream()) {
                input.transferTo(outputStream);
            } catch (IOException e) {
                // 客户端断开等写入异常不可恢复，仅记日志不抛出（已开始写响应体，无法再改状态码）。
                log.warn("问诊图片透传中断：{}", e.getMessage());
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .body(body);
    }
}
