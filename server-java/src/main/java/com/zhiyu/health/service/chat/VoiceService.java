package com.zhiyu.health.service.chat;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.agentclient.AgentClient.VoiceAgentException;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音双向业务编排（票 45，ADR-0020）。
 *
 * 只做转发与降级决策，不持久化、不进 agent_call_logs trace。入口审计在此落脱敏日志：
 * 调用类型（asr/tts）+ 参数类型（audio/text）+ 结果码/长度，绝不记音频与识别/合成文字原文
 * （硬约束 5）。未配置/超时/失败三情况降级为文字输入，不阻塞演示。
 *
 * 契约开关（asr_enabled/tts_enabled）由 AgentClient 在转发前前置检查，运行时密钥检测
 * 兜底；本 service 额外在出口把 VoiceAgentException 映射为 ApiException，文案用契约
 * degradeHint，用户始终看到统一的降级提示而非 server-py 原始错误。
 */
@Service
public class VoiceService {

    private static final Logger auditLog = LoggerFactory.getLogger("voice-audit");

    private final AgentClient agentClient;
    private final Contracts contracts;

    public VoiceService(AgentClient agentClient, Contracts contracts) {
        this.agentClient = agentClient;
        this.contracts = contracts;
    }

    /** 语音识别：录音 multipart -> 识别文字。失败抛 ApiException（携稳定码 + 降级文案）。 */
    public AsrResult recognize(MultipartFile audio, Long patientId) {
        Contracts.Voice voice = contracts.voice();
        long startedAt = System.currentTimeMillis();
        try {
            String text = agentClient.recognizeSpeech(audio);
            // 审计只记结果码 + 识别文字长度 + 耗时 + 患者标识，不记原文（硬约束 5）
            // elapsedMs 用于区分慢在上传/转发还是慢在火山推理（对照 server-py 侧耗时日志）
            auditLog.info(
                    "voice type=asr param=audio result=success patientId={} textLen={} elapsedMs={}",
                    patientId,
                    text.length(),
                    System.currentTimeMillis() - startedAt);
            return new AsrResult(text);
        } catch (VoiceAgentException e) {
            auditLog.info(
                    "voice type=asr param=audio result=fail patientId={} code={} elapsedMs={}",
                    patientId,
                    e.code(),
                    System.currentTimeMillis() - startedAt);
            throw new ApiException(e.status(), e.code(), voice.degradeHint());
        }
    }

    /**
     * 语音合成：text -> 二进制音频 + content-type。messageId 仅用于审计关联（哪条 AI 消息被播报），
     * 不参与合成。失败抛 ApiException（携稳定码 + 降级文案）。
     */
    public TtsResult synthesize(String text, Long messageId, Long patientId) {
        Contracts.Voice voice = contracts.voice();
        try {
            byte[] audio = agentClient.synthesizeSpeech(text);
            // 审计只记结果码 + 合成音频长度 + 关联标识，不记合成文字原文与音频内容（硬约束 5）
            auditLog.info(
                    "voice type=tts param=text result=success patientId={} messageId={} audioLen={}",
                    patientId,
                    messageId,
                    audio.length);
            // 开通后格式由 contracts/voice.json tts_format 钉死（如 audio/mpeg）；
            // 骨架阶段 tts_format=null，用通用二进制类型占位
            String contentType = voice.ttsFormat() == null ? "application/octet-stream" : voice.ttsFormat();
            return new TtsResult(audio, contentType);
        } catch (VoiceAgentException e) {
            auditLog.info(
                    "voice type=tts param=text result=fail patientId={} messageId={} code={}",
                    patientId,
                    messageId,
                    e.code());
            throw new ApiException(e.status(), e.code(), voice.degradeHint());
        }
    }

    /** ASR 识别结果：识别文字回端侧填输入框（可见可改、不自动发）。 */
    public record AsrResult(String text) {}

    /** TTS 合成结果：音频字节 + content-type，由 controller 透传为响应体。 */
    public record TtsResult(byte[] audio, String contentType) {}
}
