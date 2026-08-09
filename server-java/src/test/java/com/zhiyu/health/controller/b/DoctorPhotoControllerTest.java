package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.controller.staff.organization.DoctorPhotoController;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.common.MinioStorageService.PhotoContent;
import com.zhiyu.health.support.StaffTokens;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 医生照片上传/回拉 HTTP seam（票 54）。
 *
 * <p>用 @WebMvcTest 挂 AuthFilter + AdminInterceptor，验证 admin 鉴权与文件校验、
 * MinIO 旁路降级（storePhoto 返回空时不报错）。
 */
@WebMvcTest(DoctorPhotoController.class)
class DoctorPhotoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MinioStorageService minioStorage;

    @MockitoBean
    private Contracts contracts;

    private static final byte[] PNG_BYTES = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};

    @BeforeEach
    void stubLimits() {
        // 文件类型/大小上限来自契约（contracts/doctor-photo-limits.json）：2MB、JPEG/PNG
        when(contracts.doctorPhotoLimits())
                .thenReturn(new Contracts.DoctorPhotoLimits(2L * 1024 * 1024, List.of("image/jpeg", "image/png"), 1));
    }

    @Test
    void uploadReturnsObjectKeyAndUrl() throws Exception {
        when(minioStorage.storePhoto(any())).thenReturn(Optional.of("photos/2026-08-07/abc.jpg"));
        MockMultipartFile image = new MockMultipartFile("file", "lin.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/b/doctors/photos").file(image).with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.object_key").value("photos/2026-08-07/abc.jpg"))
                .andExpect(jsonPath("$.url").value("/api/b/photos?key=photos/2026-08-07/abc.jpg"));
    }

    @Test
    void uploadRejectsDoctorRole() throws Exception {
        MockMultipartFile image = new MockMultipartFile("file", "lin.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/b/doctors/photos")
                        .file(image)
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    @Test
    void uploadRejectsUnauthorized() throws Exception {
        MockMultipartFile image = new MockMultipartFile("file", "lin.jpg", "image/jpeg", new byte[] {1, 2, 3});

        // 无 Authorization 头：AuthFilter 直接 401，不进入 controller
        mockMvc.perform(multipart("/api/b/doctors/photos").file(image)).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadDegradesWhenMinioDisabled() throws Exception {
        // 旁路降级：MinIO 未启用或写入失败，storePhoto 返回空，上传不报错，返回空 key
        when(minioStorage.storePhoto(any())).thenReturn(Optional.empty());
        MockMultipartFile image = new MockMultipartFile("file", "lin.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/b/doctors/photos").file(image).with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.object_key").value(""))
                .andExpect(jsonPath("$.url").value(""));
    }

    @Test
    void uploadRejectsOversizedFile() throws Exception {
        // 2MB + 1 字节超限：controller 校验直接 400，不进入 service
        byte[] tooBig = new byte[(int) (2L * 1024 * 1024) + 1];
        MockMultipartFile image = new MockMultipartFile("file", "big.jpg", "image/jpeg", tooBig);

        mockMvc.perform(multipart("/api/b/doctors/photos").file(image).with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("照片不能超过 2MB"));
        verifyNoInteractions(minioStorage);
    }

    @Test
    void uploadRejectsUnsupportedType() throws Exception {
        // GIF 不在白名单：controller 校验直接 400
        MockMultipartFile image = new MockMultipartFile("file", "avatar.gif", "image/gif", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/b/doctors/photos").file(image).with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("照片仅支持 JPEG/PNG 格式"));
        verifyNoInteractions(minioStorage);
    }

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        MockMultipartFile image = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        mockMvc.perform(multipart("/api/b/doctors/photos").file(image).with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("请选择照片"));
        verifyNoInteractions(minioStorage);
    }

    @Test
    void getPhotoStreamsImageBytes() throws Exception {
        when(minioStorage.getObject("photos/2026-08-07/abc.png"))
                .thenReturn(Optional.of(new PhotoContent(new ByteArrayInputStream(PNG_BYTES), "image/png")));

        // StreamingResponseBody 走异步分发：必须显式 asyncDispatch，否则断言时机取决于异步线程调度。
        MvcResult async = mockMvc.perform(get("/api/b/photos")
                        .param("key", "photos/2026-08-07/abc.png")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(PNG_BYTES));
    }

    @Test
    void getPhotoReturns404WhenMissing() throws Exception {
        when(minioStorage.getObject("photos/2026-08-07/missing.jpg")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/b/photos")
                        .param("key", "photos/2026-08-07/missing.jpg")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPhotoRejectsNonObjectKey() throws Exception {
        // 非 object key 格式一律 404，防路径穿越，且不发起 MinIO 调用
        mockMvc.perform(get("/api/b/photos")
                        .param("key", "../etc/passwd")
                        .with(StaffTokens.withRole(StaffUser.ROLE_ADMIN)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(minioStorage);
    }

    @Test
    void getPhotoRejectsDoctorRole() throws Exception {
        mockMvc.perform(get("/api/b/photos")
                        .param("key", "photos/2026-08-07/abc.jpg")
                        .with(StaffTokens.withRole(StaffUser.ROLE_DOCTOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("仅管理员可操作"));
    }

    // 票 54：读取接口同样需鉴权，无 Authorization 头时 AuthFilter 直接 401，不进入 controller
    @Test
    void getPhotoRejectsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/b/photos").param("key", "photos/2026-08-07/abc.jpg"))
                .andExpect(status().isUnauthorized());
    }
}
