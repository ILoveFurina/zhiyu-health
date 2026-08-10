# 模块14：演示运营（比赛向）

## 业务概述

演示武器包是比赛评审专用的支撑能力，包含三件套：**演示看板**（今日挂号量、科室分布、号源使用率、Agent 对话量与工具调用次数）、**演示重置**（一键清空演示业务数据并重灌 seed）、**知识源现场切换**（B 端切换 RAG / 知识图谱 / 裸 LLM，C 端发新对话即见对比效果）。ADR-0022 明确它不是医疗业务实体，生产环境绝不该有一键清数据的能力，因此全部 HTTP 入口收口在 `/api/b/demo/**` 前缀下与业务 CRUD 物理隔离，并由三重保护让其在非演示环境"形同虚设"。票 48 曾有的纯展示层 Mock 药店库存同步已随票 88 退出（ADR-0035）：药品履约改为一院区一药房，由全局药师在 B 端人工推进模拟履约状态机。

## 业务流程

1. **演示看板**：管理员（admin 角色）登录 B 端 React 管理后台 → 打开"演示武器包"页（`admin/src/pages/Demo/index.tsx`）→ `GET /api/b/demo/dashboard` → `AdminInterceptor` 强制 admin 鉴权 → `DemoDashboardService` 用 JdbcTemplate 对 PG 做只读聚合 → 返回严格四类指标给前端用 Statistic 卡片 + AntV Column 图渲染。
2. **知识源现场切换**：管理员在 B 端点选 rag/graph/none → `PUT /api/b/demo/knowledge-source` → `DemoKnowledgeSourceService` 校验值域后写 Redis 全局单键 `demo:knowledge_source` → 患者在 C 端小程序发起新对话（未显式带 `knowledge_source`）→ `ChatRoundService.resolveKnowledgeSource` 读全局键补位透传给 server-py → 评审现场对比三种知识源的回答效果；server-py 完全不感知开关存在。
3. **演示重置**：管理员在 `.env` 预设 `DEMO_RESET_ENABLED=true` → B 端输入确认短语并二次确认 → `POST /api/b/demo/reset` → 三重保护（env 开关 / 确认短语 / 进程内 CAS 互斥锁）全部满足才执行 → 七步顺序：冻结 C 端 → 清 Redis 演示键 → TRUNCATE PG 演示业务表 → 重灌幂等 seed → 经 `SlotAccounting` 重建 Redis 号源计数 → 解冻 → 一致性断言 → 成功 200；中途失败返回 503 与"已完成/失败/待执行"步骤清单且**保持冻结**（`DemoFreezeFilter` 拦截全部 `/api/c/**` 返回 503），演示者可从失败步幂等重跑。
4. **schema 变更后的整库重建（开发期）**：改动 `schema.sql` 的票完成后，AI 在本地运行 `scripts/reset_zhiyu.py`（drop + recreate + seed 云演示库 zhiyu，硬断言库名）→ 重启 server-java 补种 `staff_users` → 运行 `scripts/verify_zhiyu.py` 只读验证 schema 形状与 seed 基线。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| controller | `/api/b/demo/**` 全部入口收口，只装配不承载逻辑 | `server-java/src/main/java/com/zhiyu/health/controller/staff/demo/DemoController.java` |
| service | 演示重置三重保护与七步编排、一致性断言 | `server-java/src/main/java/com/zhiyu/health/service/demo/DemoResetService.java` |
| service | 知识源现场切换（Redis 全局单键读写） | `server-java/src/main/java/com/zhiyu/health/service/demo/DemoKnowledgeSourceService.java` |
| service | 演示看板只读聚合（JdbcTemplate 直组 record） | `server-java/src/main/java/com/zhiyu/health/service/demo/DemoDashboardService.java` |
| config | 冻结闸门（进程内 AtomicBoolean）与 C 端冻结过滤器 | `server-java/src/main/java/com/zhiyu/health/config/DemoFreezeGate.java`、`DemoFreezeFilter.java` |
| config | 过滤器装配：order 25，仅拦 `/api/c/*` | `server-java/src/main/java/com/zhiyu/health/config/WebConfig.java` |
| service | 知识源补位：请求未带值时读全局键 | `server-java/src/main/java/com/zhiyu/health/service/chat/ChatRoundService.java` |
| B 端页面 | 演示武器包页：看板 / 切换 / 重置三件套 | `admin/src/pages/Demo/index.tsx` |
| B 端服务 | demo API 封装与 TS 类型 | `admin/src/services/demo.ts` |
| 契约 | 演示武器包常量单一事实源 | `contracts/demo-arsenal.json` |
| 脚本 | 云演示库 drop + recreate + seed | `scripts/reset_zhiyu.py` |
| 脚本 | 重建后只读验证 | `scripts/verify_zhiyu.py` |

## 核心代码走读

### 14.1 入口收口：`/api/b/demo/**` 与 admin 鉴权

`server-java/src/main/java/com/zhiyu/health/controller/staff/demo/DemoController.java:31-53`

```java
@RestController
@RequestMapping("/api/b/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoResetService resetService;
    private final DemoDashboardService dashboardService;
    private final DemoKnowledgeSourceService knowledgeSourceService;
    private final DemoPharmacySyncService pharmacySyncService;

    public record ResetRequest(@NotBlank String confirm) {}

    public record KnowledgeSourceRequest(String knowledgeSource) {}

    public record KnowledgeSourceView(String knowledgeSource) {}

    @PostMapping("/reset")
    public ResponseEntity<ResetResult> reset(@RequestBody ResetRequest request) {
        ResetResult result = resetService.reset(request.confirm());
        // 成功 200；中途失败 503（保持冻结，演示者可据 pendingSteps 重跑）
        return ResponseEntity.status(result.success() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(result);
    }
```

ADR-0022 的第一条边界就是路径前缀：`/api/b/demo/**` 与业务 CRUD 的 `/api/b/**` 物理分离，任何读者从路径即可一眼区分"这是 demo 工具还是业务能力"。鉴权不单独写：`WebConfig` 里 `AdminInterceptor` 拦截整个 `/api/b/**`（排除 auth/reception），demo 前缀天然落在其内。controller 本身零业务逻辑，重置的成功/失败统一用 `ResetResult` 形状返回，仅映射 200/503，方便演示者观察步骤清单。

### 14.2 演示重置的三重保护

`server-java/src/main/java/com/zhiyu/health/service/demo/DemoResetService.java:91-109`

```java
public ResetResult reset(String confirm) {
    // 保护一：env 开关
    if (!resetEnabled) {
        throw new ApiException(403, "演示重置未开启");
    }
    // 保护二：确认短语
    if (confirm == null || !confirm.equals(contracts.demoArsenal().resetConfirmPhrase())) {
        throw new ApiException(400, "确认短语不匹配");
    }
    // 保护三：进程内互斥锁
    if (!running.compareAndSet(false, true)) {
        throw new ApiException(409, "重置进行中");
    }
    try {
        return executeSteps();
    } finally {
        running.set(false);
    }
}
```

三重保护**同时满足**才执行，任一不满足直接抛 `ApiException`（统一 advice 出口）：① env 开关 `DEMO_RESET_ENABLED` 默认 `false`——任何环境首次部署该接口即拒绝，生产不设置即天然关闭；② 确认短语必须与 `contracts/demo-arsenal.json` 的 `reset_confirm_phrase`（`DEMO_RESET_CONFIRM`）完全一致，防前端误触；③ 进程内 `AtomicBoolean` CAS 互斥锁，重置进行中再次调用返回 409。ADR-0022 明确否决了 Redis 分布式锁（单实例拓扑下是净成本）与 Spring Profile（过度工程）。

### 14.3 七步重置编排与失败语义

`server-java/src/main/java/com/zhiyu/health/service/demo/DemoResetService.java:111-150`

```java
private ResetResult executeSteps() {
    List<String> completed = new ArrayList<>();
    for (String step : RESET_STEPS) {
        try {
            runStep(step, completed);
            completed.add(step);
        } catch (RuntimeException e) {
            // 中途失败保持冻结（不调 unfreeze），返回步骤清单；接口幂等可从失败步重跑
            log.warn(
                    "demo reset failed at step={} completed={} error={}",
                    step,
                    completed,
                    e.getClass().getSimpleName());
            return new ResetResult(
                    false,
                    completed,
                    step,
                    pendingSteps(step),
                    freezeGate.isFrozen(),
                    Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
    return new ResetResult(true, completed, null, List.of(), false, Map.of());
}
```

七步固定为 `freeze → clear_redis → truncate_tables → reseed → rebuild_redis → unfreeze → assert`（`DemoResetService.java:69-70`）。失败语义是这套设计的关键：**中途失败不自动回滚**（回滚本身也要清表，对 demo 无意义，重置目标本就是回到 seed），而是保持冻结、返回"已完成/失败/待执行"清单；由于 `freezeGate.freeze()` 对已冻结幂等，接口可以从失败步整体重跑。B 端页面用 `STEP_LABELS`（`admin/src/pages/Demo/index.tsx:37-45`）把步骤码翻译成中文分三列展示，失败时额外亮"已冻结 C 端"橙色 Tag。

### 14.4 清表边界与一致性断言

`server-java/src/main/java/com/zhiyu/health/service/demo/DemoResetService.java:176-204`（节选 177-180）与 `:215-234`

```java
/** 清 PG 演示业务表：TRUNCATE CASCADE 按外键逆序。保留 staff_users 与参考数据。 */
private void truncateDemoTables() {
    // RESTART IDENTITY 让 schedules 等表序列归零，重灌 seed 显式 id 从 1 起不撞旧序列
    jdbc.execute("TRUNCATE TABLE " + String.join(", ", DEMO_TABLES) + " RESTART IDENTITY CASCADE");
}
```

```java
Long effectiveAppointments =
        jdbc.queryForObject("SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'", Long.class);
long effective = effectiveAppointments == null ? 0 : effectiveAppointments;
if (slotConsumed != effective) {
    throw new IllegalStateException("号源消耗与有效挂号数不一致 consumed=" + slotConsumed + " appointments=" + effective);
}
assertKnowledgeBaselines();
```

清表边界精确：`DEMO_TABLES`（`:48-67`）只含 18 张演示业务表、按外键依赖逆序排列，`staff_users` 与参考数据（科室、医生、药品）保留；`RESTART IDENTITY` 让序列归零，保证 seed 显式 id 从 1 起不撞旧序列。**知识基线绝不被触碰**：pgvector `knowledge_chunks` 与 Neo4j 图谱只做只读数量断言（`assertKnowledgeBaselines`，`:238-265`），Neo4j 会话显式用 `AccessMode.READ` 打开。断言的核心不变量是"每个 Redis 号源计数 == PG remaining_slots 且 sum(total-remaining) == 有效挂号数"，断言失败抛异常则 unfreeze 步骤不会到达，系统保持冻结——这就是"重置失败宁可停住也不带病放行"的保护哲学。重建 Redis 计数只经 `SlotAccounting.withInitialization`（`:195-204`），满足"号源只经 SlotAccounting"的 ArchUnit 强约束。

### 14.5 冻结闸门与 C 端冻结过滤器

`server-java/src/main/java/com/zhiyu/health/config/DemoFreezeGate.java:18-30`

```java
/** 重置开始时冻结；已冻结返回 false（调用方据此判重复进入）。 */
public boolean freeze() {
    return frozen.compareAndSet(false, true);
}

/** 重置完成后解冻。 */
public void unfreeze() {
    frozen.set(false);
}

public boolean isFrozen() {
    return frozen.get();
}
```

`server-java/src/main/java/com/zhiyu/health/config/DemoFreezeFilter.java:25-36`

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
    if (freezeGate.isFrozen()) {
        ApiErrorBody.write(
                response,
                contracts.demoArsenal().resetFreezeStatus(),
                contracts.demoArsenal().resetFreezeMessage());
        return;
    }
    filterChain.doFilter(request, response);
}
```

冻结状态是进程内 `AtomicBoolean`，重启即解冻（ADR-0022 认为可接受的人工步骤）。`DemoFreezeFilter` 由 `WebConfig.java:70-82` 装配：只挂 `/api/c/*`，order 25（鉴权 20 之后、限流 30 之前）——**只冻结 C 端**，B 端只读与重置接口本身不受影响，演示者才能从 B 端观察断言结果并重跑。冻结状态码 503 与文案"演示重置中，请稍后重试"从契约加载（AGENTS.md §4：契约值只从 `contracts/` 加载）。

### 14.6 知识源现场切换：写键与逐请求补位

`server-java/src/main/java/com/zhiyu/health/service/demo/DemoKnowledgeSourceService.java:36-43`

```java
/** 写全局键；非法值 400。 */
public void update(String knowledgeSource) {
    if (knowledgeSource == null
            || !contracts.demoArsenal().knowledgeSourceValues().contains(knowledgeSource)) {
        throw new ApiException(400, "不支持的知识源值");
    }
    redis.opsForValue().set(contracts.demoArsenal().knowledgeSourceRedisKey(), knowledgeSource);
}
```

`server-java/src/main/java/com/zhiyu/health/service/chat/ChatRoundService.java:391-397`

```java
private String resolveKnowledgeSource(String requested) {
    if (requested != null && !requested.isBlank()) {
        return requested;
    }
    String global = redis.opsForValue().get(contracts.demoArsenal().knowledgeSourceRedisKey());
    return (global == null || global.isBlank()) ? null : global;
}
```

ADR-0021 的设计是"Redis 单键 + 逐请求补位"：B 端写键时用契约值域 `["rag","graph","none"]` 白名单校验；C 端对话请求**显式带值优先**，未带值才读全局键补位，两者皆空则省略字段交 server-py 按 scenario 默认处理——优先级"请求 > 全局键 > scenario 默认"，且 server-py 完全不感知开关存在。开关是串行现场切换（切一次、发新对话看效果），不做并行三路对比；状态非持久，Redis 重启或键被重置清空后回到默认 `none`。B 端三枚 Radio.Button 的值域同样来自 `@/contracts/demoArsenal`（`admin/src/pages/Demo/index.tsx:28`），双栈共享同一份契约。

## 契约与 ADR

- `contracts/demo-arsenal.json`：演示武器包跨栈常量单一事实源——确认短语、知识源值域/默认值/Redis 键、知识基线数量断言阈值、冻结状态码与文案。
- `docs/adr/0022-demo-arsenal-boundary-and-protection.md`：演示武器包边界——`/api/b/demo/**` 收口、重置三重保护、进程内互斥锁（否决 Redis 分布式锁与 Spring Profile）。
- `docs/adr/0021-knowledge-source-live-toggle-redis-key.md`：知识源现场切换——Redis 单键 + 逐请求补位，server-py 不感知。
- `docs/adr/0035-campus-pharmacy-and-simulated-fulfillment.md`：一院区一药房与全局药师模拟履约闭环（supersedes ADR-0026 平台自营药房；票 48 的 Mock 药店库存同步随之退出）。
- `docs/adr/0010-cross-stack-contracts.md`（跨栈契约，注意与 `0010-rag-knowledge-retrieval.md` 区分）：契约值只从 `contracts/` 加载的总约定，demo-arsenal 双栈读取即遵循此 ADR。

## 讲解提示

- **教学强调点一：演示能力与业务能力必须物理隔离。** 比赛 demo 需要"一键回到初始状态"，但生产系统绝不该有清数据接口。本模块的答案是三重防线叠加：路径前缀收口（边界可见）、env 开关默认关闭（部署即安全）、确认短语 + CAS 互斥（防误触与并发）。让学生体会"保护机制的成本要与拓扑匹配"——单实例就用 `AtomicBoolean`，不为 demo 引入分布式锁。
- **教学强调点二：失败语义比成功路径更值得设计。** 重置不做自动回滚，而是"保持冻结 + 步骤清单 + 幂等重跑"——回滚本身也要清表，对目标是回到 seed 的场景无意义；冻结住 C 端能阻止用户在不一致状态下继续产生数据。
- **常见提问：为什么知识基线（pgvector/Neo4j）只断言不清空？** 答：知识图谱与向量库是版本化只读基线，由独立的离线步骤（`seed-knowledge.sql` 向量回填）维护，不属于业务数据生命周期；重置只清"演示产生的业务数据"，断言只是验证基线没被破坏（Neo4j 会话还显式用只读模式打开）。
- **常见提问：`reset_zhiyu.py` 和 `/api/b/demo/reset` 有什么区别？** 答：前者是开发期整库重建（drop + recreate + seed，处理 schema 演进，硬断言只动 zhiyu 库），后者是运行期业务数据重置（保留 schema 与参考数据，供演示现场快速回到 seed 状态）；后者重灌的 `seed.sql` 与前者复用同一脚本文件保证一致。重建后必须重启 server-java 补种 `staff_users` 再跑 `verify_zhiyu.py`（verify 会断言该表行数）。

> 返回目录：[docs/textbook/README.md](./README.md)
