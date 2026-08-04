package com.zhiyu.health.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 拍照分析原图持久化的 MinIO 客户端（ADR-0023）。
 *
 * <p>仅在 zhiyu.minio.enabled=true 时装配 MinioClient；未启用时不创建任何远程连接，
 * MinioStorageService 据此走旁路降级（不留原图但分析主流程正常完成）。云端 MinIO 部署
 * 就绪后置 true，本地开发默认 false 以避免依赖未部署的远端服务。
 */
@Configuration
public class MinioClientConfig {

    @Bean
    @ConditionalOnProperty(name = "zhiyu.minio.enabled", havingValue = "true")
    MinioClient minioClient(
            @Value("${zhiyu.minio.endpoint}") String endpoint,
            @Value("${zhiyu.minio.access-key}") String accessKey,
            @Value("${zhiyu.minio.secret-key}") String secretKey,
            @Value("${zhiyu.minio.bucket}") String bucket) {
        // bucket 不参与客户端构建，仅在 service 首次调用时确保存在；此处仅校验非空。
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("zhiyu.minio.bucket 未配置");
        }
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
