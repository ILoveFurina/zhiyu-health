package com.zhiyu.health.controller.staff.organization;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.common.MinioStorageService.PhotoContent;
import com.zhiyu.health.service.vision.PhotoObjectKeys;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * B 端医生照片上传与回拉（票 54）。
 *
 * <p>仅 admin 角色可访问（AdminInterceptor 在 /api/b/** 生效）。上传复用
 * {@link MinioStorageService#storePhoto} 的旁路持久化：MinIO 未启用或写入失败时返回占位
 * 响应（空 object_key），不阻塞医生档案保存--照片为可选项。回拉走 server-java 代理流式
 * 返回，避免 bucket 公共读；object_key 即取图凭证（与 C 端 PhotoController 同语义）。
 *
 * <p>文件类型/大小上限从 {@link Contracts.DoctorPhotoLimits} 读取，与 admin 前端共享同一
 * 契约事实源（contracts/doctor-photo-limits.json）。
 */
@RestController
@RequestMapping("/api/b")
@RequiredArgsConstructor
@Slf4j
public class DoctorPhotoController {

    private final MinioStorageService minioStorage;
    private final Contracts contracts;

    /** 上传医生照片：返回 object_key 与可访问 url（url 走 /api/b/photos 代理）。 */
    @PostMapping("/doctors/photos")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        validate(file);
        Optional<String> objectKey = minioStorage.storePhoto(file);
        // 旁路降级：MinIO 不可用/写入失败时返回空 key，前端据此不写入 photo_url，不阻塞档案保存。
        String key = objectKey.orElse("");
        String url = key.isEmpty() ? "" : "/api/b/photos?key=" + key;
        return Map.of("object_key", key, "url", url);
    }

    /** 按 object_key 回拉医生照片并流式透传（鉴权后访问，bucket 不开公共读）。 */
    @GetMapping("/photos")
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
                log.warn("医生照片透传中断：{}", e.getMessage());
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .body(body);
    }

    // controller 只做校验与装配：文件类型/大小不合法直接 400，不进入 service。
    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择照片");
        }
        Contracts.DoctorPhotoLimits limits = contracts.doctorPhotoLimits();
        if (file.getSize() > limits.maxBytes()) {
            throw new ApiException(400, "照片不能超过 " + (limits.maxBytes() / 1024 / 1024) + "MB");
        }
        String type = file.getContentType();
        if (type == null || !limits.allowedTypes().contains(type)) {
            throw new ApiException(400, "照片仅支持 JPEG/PNG 格式");
        }
    }
}
