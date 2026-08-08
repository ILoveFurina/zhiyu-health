package com.zhiyu.health.service.consultation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.consultation.OnlineConsultationMessage;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMessageMapper;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.consultation.OnlineConsultationViews.MessageView;
import com.zhiyu.health.service.consultation.mapping.OnlineConsultationDtoMapper;
import com.zhiyu.health.service.vision.PhotoFileTypes;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** 医患消息的持久化与图片对象旁路；身份、状态和接诊方式守卫由问诊入口先行完成。 */
final class OnlineConsultationMessaging {
    private final OnlineConsultationMessageMapper messageMapper;
    private final OnlineConsultationDtoMapper dtoMapper;
    private final Contracts contracts;
    private final MinioStorageService minioStorage;
    private final ObjectMapper objectMapper;

    OnlineConsultationMessaging(
            OnlineConsultationMessageMapper messageMapper,
            OnlineConsultationDtoMapper dtoMapper,
            Contracts contracts,
            MinioStorageService minioStorage,
            ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.dtoMapper = dtoMapper;
        this.contracts = contracts;
        this.minioStorage = minioStorage;
        this.objectMapper = objectMapper;
    }

    List<MessageView> list(long consultationId, long afterId) {
        return messageMapper.selectAfterId(consultationId, afterId).stream()
                .map(dtoMapper::toMessageView)
                .toList();
    }

    MessageView append(long consultationId, String senderType, String kind, String content) {
        OnlineConsultationMessage message = new OnlineConsultationMessage();
        message.setConsultationId(consultationId);
        message.setSenderType(senderType);
        message.setKind(kind);
        message.setContent(content);
        messageMapper.insert(message);
        return dtoMapper.toMessageView(message);
    }

    /** 图片是问诊消息本体；MinIO 失败必须拒绝发送，不能留下无法回看的空消息。 */
    MessageView sendImage(long consultationId, String senderType, MultipartFile file) {
        Contracts.ConsultationPhotoLimits limits = contracts.consultationPhotoLimits();
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择图片");
        }
        if (file.getSize() > limits.maxBytes()) {
            throw new ApiException(400, "图片不能超过 " + (limits.maxBytes() / 1024 / 1024) + "MB");
        }
        if (!PhotoFileTypes.isAllowedImage(file, limits.allowedTypes())) {
            throw new ApiException(400, "图片仅支持 JPEG/PNG 格式");
        }
        String mediaType = PhotoFileTypes.detectMediaType(file);
        String objectKey = minioStorage.storePhoto(file).orElseThrow(() -> new ApiException(503, "图片发送失败，请稍后重试"));
        ObjectNode content =
                objectMapper.createObjectNode().put("object_key", objectKey).put("media_type", mediaType);
        return append(consultationId, senderType, OnlineConsultationMessage.KIND_IMAGE, content.toString());
    }
}
