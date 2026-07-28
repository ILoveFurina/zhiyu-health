package com.zhiyu.health.service;

/** 会话不存在或不属于当前患者；不区分原因以免泄露存在性。 */
public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException() {
        super("会话不存在");
    }
}
