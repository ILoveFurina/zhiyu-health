package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiyu.health.service.vision.PhotoUrls;
import org.junit.jupiter.api.Test;

/** 票 59 C 端图片访问约定：object key → /api/c/photos 代理相对 URL；空 key 返回空串。 */
class PhotoUrlsTest {

    @Test
    void mapsObjectKeyToCProxyUrl() {
        assertThat(PhotoUrls.cUrl("photos/2026-08-07/lin-zhiyuan.jpg"))
                .isEqualTo("/api/c/photos?key=photos/2026-08-07/lin-zhiyuan.jpg");
    }

    @Test
    void emptyOrNullKeyYieldsEmptyUrl() {
        assertThat(PhotoUrls.cUrl(null)).isEmpty();
        assertThat(PhotoUrls.cUrl("")).isEmpty();
        assertThat(PhotoUrls.cUrl("   ")).isEmpty();
    }
}
