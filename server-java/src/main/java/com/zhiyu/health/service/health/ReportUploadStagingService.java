package com.zhiyu.health.service.health;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 适配小程序单文件上传：原件仅在内存短暂存在，提交解读后立即移除。 */
@Service
public class ReportUploadStagingService {

    private static final Duration EXPIRES_AFTER = Duration.ofMinutes(5);
    // 上传限制唯一事实源是 contracts/upload-limits.json（两端入口校验必须一致）
    private final Contracts contracts;
    private final Map<UploadKey, Batch> batches = new HashMap<>();

    public ReportUploadStagingService(Contracts contracts) {
        this.contracts = contracts;
    }

    // synchronized 让清理、同批次页替换与总量校验成为一个原子步骤，避免并发页上传破坏批次。
    public synchronized UploadProgress add(
            Long patientId, String requestId, int pageIndex, int totalFiles, MultipartFile file) {
        return add(patientId, requestId, pageIndex, totalFiles, file, file == null ? null : file.getContentType());
    }

    public synchronized UploadProgress add(
            Long patientId, String requestId, int pageIndex, int totalFiles, MultipartFile file, String mediaType) {
        purgeExpired();
        Contracts.UploadLimits limits = contracts.uploadLimits();
        if (requestId == null
                || requestId.isBlank()
                || requestId.length() > 64
                || totalFiles < limits.minFiles()
                || totalFiles > limits.maxFiles()
                || pageIndex < 0
                || pageIndex >= totalFiles
                || file == null
                || file.isEmpty()
                || file.getSize() > limits.maxFileBytes()
                || !limits.allowedTypes().contains(mediaType)
                || (limits.pdfType().equals(mediaType) && totalFiles != 1)) {
            throw new ApiException(422, "报告上传参数无效");
        }
        UploadKey key = new UploadKey(patientId, requestId);
        Batch batch = batches.computeIfAbsent(key, ignored -> new Batch(totalFiles));
        if (batch.totalFiles != totalFiles) {
            throw new ApiException(409, "同一报告的文件数量不一致");
        }
        try {
            StagedFile staged = new StagedFile(file.getOriginalFilename(), mediaType, file.getBytes());
            batch.files.put(pageIndex, staged);
            batch.updatedAt = Instant.now();
        } catch (IOException e) {
            throw new ApiException(422, "无法读取上传的报告");
        }
        long totalBytes = batch.files.values().stream()
                .mapToLong(item -> item.content.length)
                .sum();
        if (totalBytes > limits.maxTotalBytes()) {
            batches.remove(key);
            throw new ApiException(422, "报告文件总量不能超过 20MB");
        }
        return new UploadProgress(batch.files.size(), totalFiles, batch.files.size() == totalFiles);
    }

    // 取出时先从共享表移除：之后即使模型失败，原文件也不会残留或被另一请求重复消费。
    public synchronized List<MultipartFile> take(Long patientId, String requestId) {
        purgeExpired();
        Batch batch = batches.get(new UploadKey(patientId, requestId));
        if (batch == null || batch.files.size() != batch.totalFiles) {
            throw new ApiException(409, "报告文件尚未上传完整");
        }
        batches.remove(new UploadKey(patientId, requestId));
        return batch.files.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(entry -> (MultipartFile) entry.getValue())
                .toList();
    }

    public synchronized void discard(Long patientId, String requestId) {
        // 与 add/take 共用同一锁，幂等命中时清理重复暂存不会和页上传交错。
        batches.remove(new UploadKey(patientId, requestId));
    }

    private void purgeExpired() {
        Instant threshold = Instant.now().minus(EXPIRES_AFTER);
        batches.entrySet().removeIf(entry -> entry.getValue().updatedAt.isBefore(threshold));
    }

    public record UploadProgress(int uploaded, int total, boolean ready) {}

    private record UploadKey(Long patientId, String requestId) {}

    private static final class Batch {
        private final int totalFiles;
        private final Map<Integer, StagedFile> files = new HashMap<>();
        private Instant updatedAt = Instant.now();

        private Batch(int totalFiles) {
            this.totalFiles = totalFiles;
        }
    }

    private static final class StagedFile implements MultipartFile {
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        private StagedFile(String originalFilename, String contentType, byte[] content) {
            this.originalFilename = originalFilename == null ? "report" : originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override
        public String getName() {
            return "files";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
