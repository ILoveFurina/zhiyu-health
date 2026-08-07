package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.service.MinioStorageService;
import com.zhiyu.health.service.MinioStorageService.PhotoContent;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 图片代理端点 HTTP seam（ADR-0023 回拉链路）：按 object_key 透传 MinIO 原图给前端。
 *
 * <p>key 即凭证（UUID 不可猜测），端点在 AuthFilter 放行；此处 standaloneSetup 不挂 AuthFilter，
 * 仅验证 controller 取图与透传语义。
 */
class PhotoControllerTest {

    @Test
    void returnsImageBytesWithContentType() throws Exception {
        // 正常回拉：MinIO 返回字节流与 media_type，controller 透传给前端。
        byte[] bytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        when(minioStorage.getObject(eq("photos/2026-08-07/abc.jpg")))
                .thenReturn(Optional.of(new PhotoContent(new ByteArrayInputStream(bytes), "image/png")));
        MockMvc mvc = standaloneSetup(new PhotoController(minioStorage))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        // StreamingResponseBody 走异步分发：必须显式 asyncDispatch，否则断言时机取决于异步线程调度而偶发空响应。
        MvcResult async = mvc.perform(get("/api/c/photos").param("key", "photos/2026-08-07/abc.jpg"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void missingObjectReturns404() throws Exception {
        // 对象不存在/MinIO 不可用：service 返回空，controller 返回 404 引导前端走无图兜底。
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        when(minioStorage.getObject(eq("photos/2026-08-07/missing.jpg"))).thenReturn(Optional.empty());
        MockMvc mvc = standaloneSetup(new PhotoController(minioStorage))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/c/photos").param("key", "photos/2026-08-07/missing.jpg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void illegalKeyReturns404() throws Exception {
        // 非 photos/ 前缀的 key 一律拒绝，防止路径穿越或访问非图片对象。
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        MockMvc mvc = standaloneSetup(new PhotoController(minioStorage))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/c/photos").param("key", "../etc/passwd")).andExpect(status().isNotFound());
    }

    @Test
    void blankKeyReturns404() throws Exception {
        // 空 key 直接 404，不发起 MinIO 调用。
        MinioStorageService minioStorage = mock(MinioStorageService.class);
        MockMvc mvc = standaloneSetup(new PhotoController(minioStorage))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/c/photos").param("key", "")).andExpect(status().isNotFound());
    }
}
