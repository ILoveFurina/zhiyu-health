package com.zhiyu.health.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/** HTTP SSE 薄适配器；轮次生命周期完全归 ChatRoundService。 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final long EMITTER_TIMEOUT_MS = 300_000L;

    private final ChatRoundService rounds;
    private final ObjectMapper objectMapper;

    public SseEmitter chat(ChatRoundService.Command command) {
        ChatRoundService.Handle handle = rounds.accept(command);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean();
        // 已转发事件数：响应按 text/event-stream 提交后再 completeWithError 只会触发
        // "No converter" 二次噪音且端侧同样收不到错误体；未转发过才允许干净 HTTP 错误（票 33）
        AtomicInteger forwarded = new AtomicInteger();
        Disposable observer = handle.events()
                .subscribe(
                        event -> send(emitter, event, closed, forwarded),
                        error -> {
                            if (closed.compareAndSet(false, true)) {
                                if (forwarded.get() == 0) {
                                    emitter.completeWithError(error);
                                } else {
                                    emitter.complete();
                                }
                            }
                        },
                        () -> {
                            if (closed.compareAndSet(false, true)) {
                                emitter.complete();
                            }
                        });
        // 断连只移除当前实时观察者；ChatRoundService 内的上游任务继续运行并持久化。
        emitter.onCompletion(observer::dispose);
        emitter.onError(error -> observer.dispose());
        emitter.onTimeout(() -> {
            observer.dispose();
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, ChatRoundService.Event event, AtomicBoolean closed, AtomicInteger forwarded) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.event()).data(objectMapper.writeValueAsString(event.data())));
            forwarded.incrementAndGet();
        } catch (IOException | IllegalStateException error) {
            closed.set(true);
            emitter.complete();
        }
    }
}
