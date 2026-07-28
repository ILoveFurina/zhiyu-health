package com.zhiyu.health.service;

import com.zhiyu.health.agentclient.AgentClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话链路骨架：组装请求体调 server-py，SSE 事件原样透传回端。
 * 会话持久化、历史组装、红线门是后续票（31）的职责，本票只做透传。
 */
@Service
public class ChatService {

    private static final long EMITTER_TIMEOUT_MS = 60_000L;

    private final AgentClient agentClient;

    public ChatService(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    public SseEmitter chat(String content, String effort, String scenario) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        Map<String, Object> body = new HashMap<>();
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        if (effort != null && !effort.isBlank()) {
            body.put("effort", effort);
        }
        if (scenario != null && !scenario.isBlank()) {
            body.put("scenario", scenario);
        }

        agentClient.chat(body).subscribe(
                event -> {
                    try {
                        // 事件名必须先于 data 写入：小程序端按行序解析（先 event 后 data）
                        SseEmitter.SseEventBuilder builder = SseEmitter.event();
                        if (event.event() != null) {
                            builder.name(event.event());
                        }
                        emitter.send(builder.data(event.data()));
                    } catch (IOException | IllegalStateException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );
        return emitter;
    }
}
