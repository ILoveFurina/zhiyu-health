# 基础模块B：横切设施

## 业务概述

横切设施是贯穿全书的地基：它们不属于任何一条业务线，但每个业务模块都踩在上面。本章讲四件事——server-java 请求入口的过滤器链（审计 → 鉴权 → 限流）、全后端统一异常出口 `ApiException`、`contracts/` 跨栈契约在 server-java / server-py / 两端前端的三种消费方式、以及 server-py 生产与测试双装配的 seam 设计（含 LLM 唯一构建点与 Windows SelectorEventLoop 强制）。免责声明兜底注入与 MinIO 旁路存储作为两条"横切到所有 AI 产出 / 所有图片"的设施一并讲完。讲完本章，后续每个业务模块只需讲"增量"。

## 业务流程

1. 任意 HTTP 请求到达 server-java，先按 order 顺序过过滤器链：`AuditFilter`（10，最外层，401/429 也要落审计）→ `AuthFilter` / `AgentCallbackAuthFilter`（20）→ `DemoFreezeFilter`（25，仅 `/api/c/*`）→ `RateLimitFilter`（30，按已认证 subject 计数）。
2. 过滤器直写错误时（如 429）经 `ApiErrorBody.write` 输出与 MVC 同形的 `{"detail": ...}` JSON。
3. 请求进入 controller → service；业务失败一律 `throw new ApiException(status, detail)`，controller 零 try-catch。
4. `ApiExceptionHandler`（`@RestControllerAdvice`）统一序列化：`ApiException` 按其 status + detail 出口；参数校验类异常统一 400；未知异常只记异常类型、返回 500 固定文案（防 SQL / 患者原文泄露进日志与响应）。
5. AI 请求链路：server-java service 经 `agentclient/` SSE 调 server-py；server-py 的 `ApplicationRuntime` 在 lifespan 里一次性装好（生产 `bootstrap.py` / 测试 `testing.py` 双装配），LLM 客户端只由 `app/core/llm.py` 构建。
6. 两端启动期 fail-fast 加载 `contracts/`：server-java `Contracts` 构造期读全部 JSON，server-py `get_contracts()` 首次访问时校验；契约缺失或损坏直接阻断启动 / 抛 `RuntimeError`。
7. AI 产出回流出栈前，`DisclaimerService` 在 server-java 出口兜底挂载"仅供参考，不替代医生诊断"（文案取自 `contracts/disclaimer.json`）；图片类产出旁路写 MinIO，失败不阻断主流程。
8. server-py 进程启动先执行 `force_selector_event_loop_on_windows()`（Windows 下 psycopg 异步要求 SelectorEventLoop），uvicorn 侧由 `scripts/run-server-py.py` patch loop factory 双保险。

## 代码地图

| 层 | 职责 | 文件路径 |
|---|---|---|
| server-java config | 过滤器装配顺序（审计 10 / 鉴权 20 / 冻结 25 / 限流 30）、CORS、B 端角色拦截器 | `server-java/src/main/java/com/zhiyu/health/config/WebConfig.java` |
| server-java config | 审计（只记脱敏摘要）、JWT 鉴权、Agent 回调密钥鉴权、单机固定窗口限流 | `config/AuditFilter.java`、`config/AuthFilter.java`、`config/AgentCallbackAuthFilter.java`、`config/RateLimitFilter.java` |
| server-java config | 统一异常类型与统一出口 advice；错误体唯一生产者 | `config/ApiException.java`、`config/ApiExceptionHandler.java`、`config/ApiErrorBody.java` |
| server-java config | 跨栈契约基座：启动期 fail-fast 加载全部契约 JSON 为不可变 record | `config/Contracts.java` |
| server-java service | 免责声明标注唯一入口（硬约束 1 出口兜底） | `service/common/DisclaimerService.java` |
| server-java service | 拍照原图 MinIO 旁路存储（写失败降级不留图，不阻断主流程） | `service/common/MinioStorageService.java` |
| server-py app | 生产装配：真实 settings / 存储客户端 / server-java 回调 / LLM 懒适配器的唯一接线点 | `server-py/app/bootstrap.py` |
| server-py app | 测试装配：显式注入 fake，不读 settings、不连存储 | `server-py/app/testing.py` |
| server-py app | 运行期依赖容器（frozen dataclass），生产与测试共用一份字段清单 | `server-py/app/runtime.py` |
| server-py app | Agent 层配置（pydantic-settings，只含 LLM 与知识检索所需项） | `server-py/app/config.py` |
| server-py core | 火山方舟 ChatOpenAI 唯一构建点；思考增量透传 | `server-py/app/core/llm.py` |
| server-py core | 跨栈契约 Python 侧加载（懒加载 + pydantic 校验 + 进程内缓存） | `server-py/app/core/contracts.py` |
| server-py core | Windows SelectorEventLoop 强制 | `server-py/app/core/eventloop.py` |
| scripts | uvicorn loop factory patch（server-py 本地启动唯一入口） | `scripts/run-server-py.py` |
| admin / miniprogram | 契约第三消费方：admin 直接 import 契约 JSON 推导 TS 类型；小程序侧为手工对齐的本地镜像 | `admin/src/contracts/*.ts`、`miniprogram/utils/emotion.js` 等 |

## 核心代码走读

### B.1 过滤器装配顺序：审计 → 鉴权 → 限流

`WebConfig` 手工装配所有过滤器（不走组件扫描），顺序用 `setOrder` 钉死。`server-java/src/main/java/com/zhiyu/health/config/WebConfig.java:36-42`：

```java
@Bean
public FilterRegistrationBean<AuditFilter> auditFilterRegistration() {
    FilterRegistrationBean<AuditFilter> bean = new FilterRegistrationBean<>(new AuditFilter());
    bean.addUrlPatterns("/api/*");
    bean.setOrder(10);
    return bean;
}
```

同一文件中：`AuthFilter` order 20（`WebConfig.java:53-59`）、`RateLimitFilter` order 30（`WebConfig.java:44-51`）、`AgentCallbackAuthFilter` 只对 `/api/agent/*` 生效（`WebConfig.java:61-68`）、`DemoFreezeFilter` order 25 插在鉴权之后限流之前（`WebConfig.java:70-82`，`@ConditionalOnBean` 是为了 `@WebMvcTest` 切片上下文不缺 Bean）。顺序的语义不是随便排的：审计必须最外层，这样 401/429 也能落审计；限流必须在鉴权之后，因为限流 key 用"已认证 subject"（无则退回 remoteAddr）。

审计的纪律在 `AuditFilter.java:26-37`——`finally` 块保证任何结果都落一行，且只记方法 / 路径 / 状态码 / 耗时 / subject / 请求体长度，绝不记请求体（硬约束 5）：

```java
} finally {
    long costMs = System.currentTimeMillis() - start;
    Object subject = request.getAttribute(AuthFilter.ATTR_AUTH_SUBJECT);
    log.info(
            "audit method={} path={} status={} costMs={} subject={} reqLen={}",
            request.getMethod(),
            request.getRequestURI(),
            response.getStatus(),
            costMs,
            subject,
            request.getContentLengthLong());
}
```

### B.2 统一异常出口：业务代码只抛 ApiException

全后端约定：service / controller 业务失败只抛 `ApiException`，controller 零 try-catch。异常本身只是一个"HTTP 状态码 + 可选业务码 + detail 文案"的载体，`server-java/src/main/java/com/zhiyu/health/config/ApiException.java:4-17`：

```java
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String detail) {
        this(status, null, detail);
    }

    public ApiException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }
```

序列化集中在 `ApiExceptionHandler`（`@RestControllerAdvice`）。`ApiExceptionHandler.java:21-26`：

```java
@ExceptionHandler(ApiException.class)
public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
    Map<String, Object> body =
            e.getCode() == null ? ApiErrorBody.of(e.getMessage()) : ApiErrorBody.of(e.getCode(), e.getMessage());
    return ResponseEntity.status(e.getStatus()).body(body);
}
```

同类的设计要点还有两个：一是参数校验类异常（`MethodArgumentNotValidException`、`BindException` 等五个）统一折叠成稳定 400 `INVALID_REQUEST`，不向客户端暴露框架内部细节（`ApiExceptionHandler.java:35-44`）；二是最终兜底只把异常**类型**写日志、返回固定 500 文案，因为异常消息可能含 SQL、连接信息或患者原文（`ApiExceptionHandler.java:46-51`）。过滤器（如限流 429）绕过 MVC 直写响应时走 `ApiErrorBody.write`（`ApiErrorBody.java:26-30`）手写同形 JSON，保证"过滤器出口"与"advice 出口"的 `{"detail": ...}` 形状完全一致——这是与前端约定好的错误契约。

### B.3 契约三端消费：contracts/ 单一事实源

仓库根 `contracts/`（24 个 JSON）是状态机、消息类型、SSE 事件名、文案的单一事实源。server-java 侧启动期 fail-fast 全量加载为不可变 record，`Contracts.java:55-70`：

```java
private Contracts(Path dir) {
    // 独立 mapper：契约 JSON 统一 snake_case，且允许 _doc 等说明性字段存在。
    ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.disclaimer = read(mapper, dir, "disclaimer.json", Disclaimer.class);
    this.sseEvents = read(mapper, dir, "sse-events.json", SseEvents.class);
    this.visionErrors = read(mapper, dir, "vision-errors.json", VisionErrors.class);
    this.uploadLimits = read(mapper, dir, "upload-limits.json", UploadLimits.class);
    this.chatDefaults = read(mapper, dir, "chat-defaults.json", ChatDefaults.class);
    this.prescriptionFlow = read(mapper, dir, "prescription-flow.json", PrescriptionFlow.class);
```

目录解析支持 `CONTRACTS_DIR` 系统属性 / 环境变量覆盖，默认 `../contracts`（`Contracts.java:92-100`）；加载失败直接抛 `IllegalStateException` 阻断启动（`Contracts.java:102-109`）——契约缺失属部署错误，不带病运行。

server-py 侧是同一思想的 Python 版：`app/core/contracts.py` 用 pydantic 模型逐文件 `model_validate`，结构不符抛 `RuntimeError`，`@lru_cache` 进程内单例。`server-py/app/core/contracts.py:294-297`：

```python
@lru_cache
def get_contracts() -> Contracts:
    """进程内单例：首次访问时加载并缓存，失败抛 RuntimeError。"""
    return _load(_contracts_dir())
```

注意两栈的消费粒度不同：server-java 加载全部 24 个契约；server-py 只登记自己消费的 16 个（`contracts.py:210-226`），按"pydantic 默认 ignore extra"约定忽略未消费字段。第三消费方是前端：admin 直接 import 契约 JSON 推导 TS 类型与常量，`admin/src/contracts/appointment.ts:1-7`：

```typescript
import flow from '../../../contracts/appointment-flow.json';

// 票 71：管理端直接从共享契约推导状态码、标签与叫号消息类型。
export const appointmentStatuses = flow.statuses;
export const appointmentStatusLabels = flow.status_labels;
export type AppointmentStatusCode = keyof typeof appointmentStatusLabels;
export const appointmentCalledMessageType = flow.called_notice.message_type;
```

小程序打包机制读不了仓库根 JSON，只能做手工对齐的本地镜像（如 `miniprogram/utils/emotion.js` 开头注释明确写着"契约变更须同步更新"）。跨栈一致性由 server-java 的 `ContractsTest` 钉死（如 SSE 流事件顺序 `[meta, knowledge, token, message, done]`），改契约必须双栈同步发版。

### B.4 免责声明兜底注入

硬约束 1：一切 AI 产出必须携带"仅供参考，不替代医生诊断"。文案的唯一来源是 `contracts/disclaimer.json`（`text` 通用文案 + `tcm_text` 中医专属文案，ADR-0024），代码中禁止另立字面量。server-py 在生成时注入，server-java 出口用 `DisclaimerService` 兜底。`server-java/src/main/java/com/zhiyu/health/service/common/DisclaimerService.java:30-40`：

```java
/** 卡片字段挂载（SSE 出口兜底）：已带正确文案的幂等跳过，缺失或被篡改的覆盖。 */
public void mount(ObjectNode card) {
    if (!text().equals(card.path("disclaimer").asText())) {
        card.put("disclaimer", text());
    }
}

/** 独立字段挂载语义：有内容才给文案，无内容返回 null（前端按字段有无渲染）。 */
public String mountIfPresent(String content) {
    return content == null ? null : text();
}
```

两个语义值得强调：`mount` 是幂等且防篡改的——server-py 已带正确文案就跳过，缺失或被改动就覆盖回权威文案；存储层只存纯内容，标注一律在响应装配时挂载，这样文案修订不需要回刷历史数据。舌诊卡片叠加通用 + 中医两条（`tcmText()`，`DisclaimerService.java:26-28`），其余 AI 产出只取通用文案。

### B.5 MinIO 旁路存储：失败降级不阻断主流程

拍照分析原图、医生头像、问诊交流图片存 MinIO（ADR-0023/0029），业务库只存对象 key。`MinioStorageService` 的核心语义是"旁路"：写失败降级为不留原图，绝不打断分析主流程。`server-java/src/main/java/com/zhiyu/health/service/common/MinioStorageService.java:68-81`：

```java
public Optional<String> storePhoto(MultipartFile file) {
    if (!enabled || minioClient == null) {
        return Optional.empty();
    }
    try {
        ensureBucket();
        String objectKey = buildObjectKey(file);
        try (var input = new ByteArrayInputStream(file.getBytes())) {
            minioClient.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(input, file.getSize(), -1)
                            .contentType(file.getContentType() != null ? file.getContentType() : "image/jpeg")
                            .build());
        }
        return Optional.of(objectKey);
```

返回 `Optional<String>` 而不是抛异常，是这个设计的签名级表达：调用方据空值决定是否落 image 消息，分析卡片照常产出。`zhiyu.minio.enabled=false` 时连远程调用都不发起（云端 MinIO 未部署也可交付）。配套细节：bucket 存在性检查用双重检查锁只做一次（`MinioStorageService.java:145-160`）；对象 key 按日期分目录 + UUID 防碰撞，不含患者隐私信息（`MinioStorageService.java:162-168`）；图片消息落库用独立事务，与分析卡片解耦，分析失败不回滚图片（`persistPhotosAndMessages`，`MinioStorageService.java:129-143`）。

### B.6 server-py 双装配 seam、LLM 唯一构建点与 Windows 事件循环强制

server-py 的依赖装配有一个清晰的 seam（接缝）：`ApplicationRuntime` 是一个 frozen dataclass 容器（`server-py/app/runtime.py:13-27`），字段只安装一次到 `app.state`，生产与测试共用同一份字段清单——避免两套装配各维护一份隐式清单导致漂移。

生产侧 `production_lifespan` 是连接真实 settings、存储客户端、server-java 回调和 LLM 懒适配器的**唯一**位置。`server-py/app/bootstrap.py:31-45`：

```python
@asynccontextmanager
async def production_lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    clients = create_knowledge_clients(settings)
    business_client = BusinessCallbackClient(
        settings.server_java_base_url, callback_secret=settings.agent_callback_secret
    )
    knowledge_retriever = build_knowledge_retriever(settings)
    graph_traverser = build_graph_traverser(clients)
    directory = CallbackDepartmentDirectory(business_client)
    runner = LazySettingsAgentRunner(
        [*build_business_tools(business_client), *build_department_tools(directory)],
        knowledge_retriever,
        graph_traverser,
    )
```

测试侧 `create_test_app`（`server-py/app/testing.py:27-68`）走同一容器但全部依赖显式入参注入：不读 settings、不连存储、不构建真实回调客户端。模块 docstring 把意图写得很直白——"测试不会进入本模块（bootstrap），因此不会因某个 fake 是否传入而意外触碰真实外部资源"。各 `Lazy*` 适配器（`LazySettingsAgentRunner`、`LazyVisionInterpreter` 等）把真实 LLM 构建推迟到首次调用，测试不传 fake 时才懒触发，这就是"测试注入 fake，生产懒装真身"的机制。

LLM 客户端全项目只有一个构建点 `build_chat_model`（ADR-0004），对话与报告解读共用，差异项以参数传入。`server-py/app/core/llm.py:52-62`：

```python
if reasoning_effort == "disabled":
    # 方舟 OpenAI 兼容接口的非标准 thinking 字段必须经 extra_body 透传。
    options["extra_body"] = {"thinking": {"type": "disabled"}}
else:
    options["reasoning_effort"] = reasoning_effort
return ArkChatOpenAI(
    model=settings.doubao_chat_model,
    base_url=settings.ark_base_url,
    api_key=SecretStr(settings.ark_api_key),
    **options,
)
```

`ArkChatOpenAI` 子类（`llm.py:17-36`）保留方舟流中的非标准 `reasoning_content` 思考增量——标准 LangChain 转换会丢弃它。

最后是平台修正：Windows 上 Python 默认 ProactorEventLoop，而 psycopg 异步模式拒绝在其上运行。`server-py/app/core/eventloop.py:16-23`：

```python
def force_selector_event_loop_on_windows() -> None:
    """Windows 上把默认事件循环策略切到 Selector；其他平台为空操作。"""
    if sys.platform == "win32":
        # 3.14 起公共别名私有化：优先取私有实现类，3.12/3.13 回退公共别名（见模块 docstring）
        policy_cls = getattr(
            asyncio, "_WindowsSelectorEventLoopPolicy", asyncio.WindowsSelectorEventLoopPolicy
        )
        asyncio.set_event_loop_policy(policy_cls())
```

该函数在 `app/main.py:15`（import 期）与集成测试、seed 脚本中各调用一次；但 uvicorn 自己起事件循环时不吃这套策略，所以本地启动必须走 `scripts/run-server-py.py`，它在 `scripts/run-server-py.py:21` 直接 patch `uvicorn.loops.auto.auto_loop_factory` 强制 `SelectorEventLoop`——双保险，缺一不可（模块 docstring 还记录了 Python 3.14 策略机制私有化弃用的演进风险）。

## 契约与 ADR

- `contracts/disclaimer.json`：硬约束 1 文案单一事实源（通用 + 中医两条），双栈共享。
- `contracts/sse-events.json`、`upload-limits.json`、`knowledge.json`、`emotion.json`、`voice.json` 等 24 个契约：全部由 server-java `Contracts` 与 server-py `core/contracts.py` 启动期加载，admin 经 `admin/src/contracts/` 直接 import，小程序侧为本地镜像。
- ADR-0010《跨栈契约：contracts/ JSON 单一事实源 + 双栈启动加载》：本章 B.3 的决策依据（注意与另一篇同号 ADR-0010《RAG 知识检索只用于受控证据问答与技术演示》区分，后者属模块 11）。
- ADR-0009《响应赛题约束的技术栈调整：B 端 React 化与后端双栈拆分》：server-java / server-py 双栈及 server-py 双装配的背景。
- ADR-0004《LLM 选型：火山引擎方舟一站式（豆包 doubao-seed-2.1）》：`core/llm.py` 唯一构建点的依据。
- ADR-0023《拍照分析原图持久化：MinIO 对象存储 + messages image kind》：MinIO 旁路语义与降级策略。
- ADR-0024《中医辨证场景合规边界：调理不出药材 + 中医专属免责 + 急症软兜底》：`tcm_text` 单列的原因。

## 讲解提示

- 教学强调：过滤器 order 不是配置细节而是语义——让学生回答"为什么审计在鉴权之前、限流在鉴权之后"，答不上来就说明没理解"401/429 也要审计"和"限流按 subject 计数"这两条需求。
- 学生常问："为什么契约坏了要阻断启动而不是降级？"答案要点：契约是跨栈单一事实源，带病运行会让两端常量不一致，故障从启动期推迟到运行期且更难定位；fail-fast 把部署错误暴露在部署时刻。
- 学生常问："测试 fake 为什么不能用 mock.patch 打进生产装配？"答案要点：seam 设计的核心是"测试不进入生产装配代码路径"——显式入参注入让依赖在类型签名里可见，patch 则是隐式改全局状态，fake 漏传时会意外触碰真实 LLM / 存储。
- 易踩坑提醒：Windows 上直接 `uvicorn` 启动 server-py 会 psycopg 崩溃，必须用 `uv run python scripts/run-server-py.py`；这是环境约束不是代码 bug，排障先看启动方式。

> 返回目录：[docs/textbook/README.md](./README.md)
