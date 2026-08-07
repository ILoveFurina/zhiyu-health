package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** MinIO object key 格式校验：危险输入触发拒绝，合法 key 不误触。 */
class PhotoObjectKeysTest {

    @Test
    void acceptsValidObjectKey() {
        assertTrue(PhotoObjectKeys.isValid("photos/2026-08-07/abc.jpg"));
        assertTrue(PhotoObjectKeys.isValid("photos/2026-08-07/lin-zhiyuan.jpeg"));
        assertTrue(PhotoObjectKeys.isValid("photos/2026-08-07/uuid-123.png"));
    }

    @Test
    void rejectsPathTraversal() {
        // 路径穿越片段必须拒绝，防访问非图片对象
        assertFalse(PhotoObjectKeys.isValid("../etc/passwd"));
        assertFalse(PhotoObjectKeys.isValid("photos/../../etc/passwd"));
        assertFalse(PhotoObjectKeys.isValid("photos/2026-08-07/../secret.jpg"));
    }

    @Test
    void rejectsArbitraryUrl() {
        // 票 54：禁止任意 URL 入库，photo_url 必须是 object key
        assertFalse(PhotoObjectKeys.isValid("https://example.com/demo/lin.jpg"));
        assertFalse(PhotoObjectKeys.isValid("http://localhost:9000/zhiyu-photos/x.jpg"));
    }

    @Test
    void rejectsNonPhotoPrefix() {
        assertFalse(PhotoObjectKeys.isValid("docs/2026-08-07/abc.jpg"));
        assertFalse(PhotoObjectKeys.isValid("/photos/2026-08-07/abc.jpg"));
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertFalse(PhotoObjectKeys.isValid("photos/2026-08-07/abc.gif"));
        assertFalse(PhotoObjectKeys.isValid("photos/2026-08-07/abc.pdf"));
        assertFalse(PhotoObjectKeys.isValid("photos/2026-08-07/abc"));
    }

    @Test
    void rejectsBlankAndMalformedDate() {
        assertFalse(PhotoObjectKeys.isValid(""));
        assertFalse(PhotoObjectKeys.isValid("   "));
        assertFalse(PhotoObjectKeys.isValid(null));
        // 日期段格式不符
        assertFalse(PhotoObjectKeys.isValid("photos/2026-8-7/abc.jpg"));
        assertFalse(PhotoObjectKeys.isValid("photos/not-a-date/abc.jpg"));
    }
}
