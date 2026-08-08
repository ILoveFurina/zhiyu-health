package com.zhiyu.health.service.vision;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** 视觉场景共享的上传边界；具体场景只提供面向用户的照片名称。 */
final class PhotoUploadValidator {

    private final Contracts contracts;

    PhotoUploadValidator(Contracts contracts) {
        this.contracts = contracts;
    }

    void validate(List<MultipartFile> files, String photoName) {
        Contracts.UploadLimits limits = contracts.uploadLimits();
        if (files == null || files.size() < limits.minFiles() || files.size() > limits.maxFiles()) {
            throw new ApiException(422, "请上传 1-5 张" + photoName + "照片");
        }
        long total = 0;
        for (MultipartFile file : files) {
            total += file.getSize();
            if (file.isEmpty()
                    || file.getSize() > limits.maxFileBytes()
                    || !PhotoFileTypes.isAllowedImage(file, limits.imageTypes())) {
                throw new ApiException(422, "仅支持规定大小的 JPEG 或 PNG " + photoName + "照片");
            }
        }
        if (total > limits.maxTotalBytes()) {
            throw new ApiException(422, photoName + "照片总量不能超过 20MB");
        }
    }
}
