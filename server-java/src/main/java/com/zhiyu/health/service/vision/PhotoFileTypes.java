package com.zhiyu.health.service.vision;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拍照上传图片类型探测（票 15/16/17/51 通用）。
 *
 * <p>支付宝小程序 {@code my.uploadFile} 不会可靠地为文件 part 设置 Content-Type（常为
 * application/octet-stream 或空），故 4 个 PhotoService 的图片类型校验除信任客户端声明的
 * content-type 外，必须回退到字节 magic bytes 探测真实格式，否则合法 JPEG/PNG 被误拒为 422。
 * 仅判定白名单内的 JPEG/PNG；非图片字节返回 false（由调用方决定错误码）。
 */
public final class PhotoFileTypes {

    private PhotoFileTypes() {}

    /**
     * 客户端声明的 content-type 在图片白名单内，或回退字节 magic 命中 JPEG/PNG。
     *
     * <p>读取失败按"非图片"处理（返回 false），与既有一致性优先：宁可拒绝也不放行可疑输入。
     */
    public static boolean isAllowedImage(MultipartFile file, java.util.List<String> imageTypes) {
        if (imageTypes.contains(file.getContentType())) {
            return true;
        }
        return isImageByMagic(file);
    }

    /**
     * 探测真实图片媒体类型：优先信任白名单内的 content-type，否则回退 magic bytes。
     *
     * <p>支付宝 my.uploadFile 常把 part Content-Type 设为 application/octet-stream 或空，
     * 直接落库会导致 media_type 失真。本方法在 magic 命中时返回标准 image/jpeg 或 image/png，
     * 均不命中时回退原始 content-type（可能为 null，调用方自行兜底）。
     */
    public static String detectMediaType(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null && (declared.equals("image/jpeg") || declared.equals("image/png"))) {
            return declared;
        }
        byte[] head;
        try {
            head = file.getBytes();
        } catch (IOException e) {
            return declared;
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (head.length >= 8
                && (head[0] & 0xFF) == 0x89
                && (head[1] & 0xFF) == 0x50
                && (head[2] & 0xFF) == 0x4E
                && (head[3] & 0xFF) == 0x47) {
            return "image/png";
        }
        return declared;
    }

    /** JPEG/PNG/WEBP magic bytes 探测；读取异常按非图片处理（调用方据此抛 422）。 */
    private static boolean isImageByMagic(MultipartFile file) {
        byte[] head;
        try {
            head = file.getBytes();
        } catch (IOException e) {
            return false;
        }
        // JPEG: FF D8 FF
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (head.length >= 8
                && (head[0] & 0xFF) == 0x89
                && (head[1] & 0xFF) == 0x50
                && (head[2] & 0xFF) == 0x4E
                && (head[3] & 0xFF) == 0x47
                && (head[4] & 0xFF) == 0x0D
                && (head[5] & 0xFF) == 0x0A
                && (head[6] & 0xFF) == 0x1A
                && (head[7] & 0xFF) == 0x0A) {
            return true;
        }
        // WEBP: "RIFF"...."WEBP"（偏移 0-3 为 RIFF，偏移 8-11 为 WEBP）
        return head.length >= 12
                && (head[0] & 0xFF) == 0x52 // R
                && (head[1] & 0xFF) == 0x49 // I
                && (head[2] & 0xFF) == 0x46 // F
                && (head[3] & 0xFF) == 0x46 // F
                && (head[8] & 0xFF) == 0x57 // W
                && (head[9] & 0xFF) == 0x45 // E
                && (head[10] & 0xFF) == 0x42 // B
                && (head[11] & 0xFF) == 0x50; // P
    }
}
