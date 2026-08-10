package com.zhiyu.health.service.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.entity.chat.Message;
import com.zhiyu.health.service.chat.ConversationService;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拍照分析原图旁路持久化（ADR-0023）。
 *
 * <p>MinIO 是旁路存储：只为"历史会话回看原照"服务，不介入视觉分析热路径。分析主流程
 * 先把图片字节流透传 server-py（interpretVision 取图链路不变），再写一份进 MinIO。
 * MinIO 写入失败<strong>不阻断分析主流程</strong>：降级为不留原图（不落 image 消息）但
 * 分析卡片正常产出回落会话，仅记可观测错误日志。
 *
 * <p>MinIO 未启用（zhiyu.minio.enabled=false）时 {@link #storePhoto} 直接返回空 Optional，
 * 不发起任何远程调用，使 14-17 在云端 MinIO 未部署时仍可交付与测试。
 */
@Service
@Slf4j
public class MinioStorageService {

    private final MinioClient minioClient;
    private final String bucket;
    private final boolean enabled;
    private final ConversationService conversations;
    private final ObjectMapper objectMapper;
    private volatile boolean bucketReady;

    public MinioStorageService(
            org.springframework.beans.factory.ObjectProvider<MinioClient> minioClientProvider,
            ConversationService conversations,
            ObjectMapper objectMapper,
            @Value("${zhiyu.minio.bucket:zhiyu-photos}") String bucket,
            @Value("${zhiyu.minio.enabled:false}") boolean enabled) {
        // enabled=false 时 MinioClient bean 不存在（ConditionalOnProperty），ObjectProvider 返回 null
        this.minioClient = minioClientProvider.getIfAvailable();
        this.bucket = bucket;
        this.enabled = enabled;
        this.conversations = conversations;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传单张照片到 MinIO，返回对象 key；不可用时返回空。
     *
     * <p>旁路语义：任何异常（连接失败、认证错误、bucket 不可创建）都吞掉并返回空，
     * 调用方据此决定是否落 image 消息。绝不抛出异常打断分析主流程。
     */
    public Optional<String> storePhoto(MultipartFile file) {
        if (!enabled || minioClient == null) {
            return Optional.empty();
        }
        try {
            ensureBucket();
            String objectKey = buildObjectKey(file);
            try (var input = new ByteArrayInputStream(file.getBytes())) {
                minioClient.putObject(
                        PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(input, file.getSize(), -1)
                                .contentType(file.getContentType() != null ? file.getContentType() : "image/jpeg")
                                .build());
            }
            return Optional.of(objectKey);
        } catch (ErrorResponseException e) {
            log.warn("MinIO 写入失败（ErrorResponse），降级为不留原图：{}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MinIO 不可用，降级为不留原图：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 按对象 key 回拉原图流，供图片代理端点透传给前端（ADR-0023 回拉链路）。
     *
     * <p>调用方负责关闭返回的 InputStream。MinIO 未启用或对象不存在时返回空，调用方据此返回 404。
     * 与 storePhoto 同为旁路语义：任何读取异常都吞掉返回空，不抛出打断请求。
     */
    public Optional<PhotoContent> getObject(String objectKey) {
        if (!enabled || minioClient == null) {
            return Optional.empty();
        }
        try {
            GetObjectResponse response = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
            // MinIO 默认对未设 content-type 的对象返回 application/octet-stream；
            // 优先用上传时记录的 media_type（image/webp 等），保证前端 <image> 正确识别。
            String mediaType = response.headers().get("Content-Type");
            if (mediaType == null || mediaType.isBlank() || "application/octet-stream".equals(mediaType)) {
                mediaType = "image/jpeg";
            }
            return Optional.of(new PhotoContent(response, mediaType));
        } catch (ErrorResponseException e) {
            log.warn("MinIO 读取失败（对象不存在或 ErrorResponse）：{}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MinIO 读取不可用：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 上传知识文档原文到 MinIO，返回对象 key；不可用时返回空（ADR-0036 旁路降级）。
     *
     * <p>与 storePhoto 同为旁路语义：MinIO 不可用时返回空，文档元数据与 chunk 正常写库，
     * 但无法重新切分（无原文可重读）。object_key 前缀为 docs/ 区别于照片。
     */
    public Optional<String> storeDocument(MultipartFile file) {
        if (!enabled || minioClient == null) {
            return Optional.empty();
        }
        try {
            ensureBucket();
            String objectKey = buildDocumentObjectKey(file);
            try (var input = new ByteArrayInputStream(file.getBytes())) {
                minioClient.putObject(
                        PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(input, file.getSize(), -1)
                                .contentType(file.getContentType() != null ? file.getContentType() : "text/plain")
                                .build());
            }
            return Optional.of(objectKey);
        } catch (ErrorResponseException e) {
            log.warn("MinIO 写入文档失败（ErrorResponse），降级为不留原文：{}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MinIO 不可用，降级为不留文档原文：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 回拉原文的字节流，供重切分读取（ADR-0036）；MinIO 不可用时返回空。 */
    public Optional<InputStream> getDocumentStream(String objectKey) {
        if (!enabled || minioClient == null) {
            return Optional.empty();
        }
        try {
            GetObjectResponse response = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return Optional.of(response);
        } catch (ErrorResponseException e) {
            log.warn("MinIO 读取文档失败（对象不存在或 ErrorResponse）：{}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MinIO 读取文档不可用：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 回拉原图的字节流与 media_type；调用方负责关闭 stream。 */
    public record PhotoContent(InputStream stream, String mediaType) {}

    /**
     * 批量持久化照片并落 image 消息；部分失败时已成功的仍落库，失败项静默跳过。
     *
     * <p>独立事务（@Transactional）：图片消息与分析卡片解耦落库，分析失败时不回滚图片，
     * 保证用户至少看到"我拍过什么"的回看价值（ADR-0023 旁路语义）。
     */
    @Transactional
    public void persistPhotosAndMessages(Long conversationId, List<MultipartFile> files) {
        for (MultipartFile file : files) {
            Optional<String> objectKey = storePhoto(file);
            if (objectKey.isEmpty()) {
                continue;
            }
            ObjectNode imageContent = objectMapper
                    .createObjectNode()
                    .put("object_key", objectKey.get())
                    .put("media_type", file.getContentType() != null ? file.getContentType() : "image/jpeg");
            conversations.appendMessage(
                    conversationId, "user", imageContent.toString(), Message.KIND_IMAGE, null, null);
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            bucketReady = true;
        }
    }

    private String buildObjectKey(MultipartFile file) {
        // 按日期分目录便于运维；UUID 防碰撞，不含患者隐私信息（硬约束 5：审计不记敏感原文）。
        String datePart = LocalDate.now().toString();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String ext = extensionOf(file);
        return "photos/" + datePart + "/" + uuid + ext;
    }

    private String extensionOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            String ext = name.substring(name.lastIndexOf('.')).toLowerCase();
            if (ext.matches("\\.(jpg|jpeg|png)")) {
                return ext;
            }
        }
        return ".jpg";
    }

    private String buildDocumentObjectKey(MultipartFile file) {
        String datePart = LocalDate.now().toString();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String ext = documentExtensionOf(file);
        return "docs/" + datePart + "/" + uuid + ext;
    }

    private String documentExtensionOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            String ext = name.substring(name.lastIndexOf('.')).toLowerCase();
            if (ext.matches("\\.(txt|md)")) {
                return ext;
            }
        }
        return ".txt";
    }
}
