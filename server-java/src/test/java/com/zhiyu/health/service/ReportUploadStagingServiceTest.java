package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.service.health.ReportUploadStagingService;
import com.zhiyu.health.support.TestContracts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ReportUploadStagingServiceTest {

    private final ReportUploadStagingService staging = new ReportUploadStagingService(TestContracts.instance());

    @Test
    void keepsPagesInClientOrderAndConsumesThemOnce() {
        staging.add(7L, "request-1", 1, 2, new MockMultipartFile("file", "second.png", "image/png", new byte[] {2}));
        staging.add(7L, "request-1", 0, 2, new MockMultipartFile("file", "first.jpg", "image/jpeg", new byte[] {1}));

        List<?> files = staging.take(7L, "request-1");

        assertThat(files).extracting("originalFilename").containsExactly("first.jpg", "second.png");
        assertThatThrownBy(() -> staging.take(7L, "request-1")).isInstanceOf(ApiException.class);
    }

    @Test
    void refusesFinalizeUntilEveryPageArrives() {
        staging.add(7L, "request-2", 0, 2, new MockMultipartFile("file", "first.jpg", "image/jpeg", new byte[] {1}));

        assertThatThrownBy(() -> staging.take(7L, "request-2"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("尚未上传完整");
    }
}
