package com.zhiyu.health.service;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.config.DemoFreezeGate;
import com.zhiyu.health.entity.Schedule;
import com.zhiyu.health.mapper.ScheduleMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

/**
 * 演示重置（票 25，ADR-0022）：受三重保护的一键业务数据重置。
 *
 * 三重保护（同时满足才执行）：① env {@code DEMO_RESET_ENABLED} 默认 false；
 * ② 请求体确认短语与契约 {@code reset_confirm_phrase} 一致；③ 进程内 {@link AtomicBoolean} CAS 互斥。
 *
 * 七步顺序固定为"冻结 C 端 -> 清 Redis 演示键 -> 清 PG 演示业务表 -> 重灌幂等 seed ->
 * 重建 Redis 计数 -> 解冻 -> 一致性断言"。中途失败保持冻结、返回可恢复步骤清单，
 * 接口幂等可从失败步重跑，不自动回滚（回滚本身也要清表，对 demo 无意义）。
 *
 * 不触碰 pgvector 知识块与 Neo4j 医学知识图谱（版本化只读基线），只在完成后做数量一致性断言。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemoResetService {

    /** 重置清空的业务演示表，按外键依赖逆序排列（被引用者后清）。 */
    private static final String[] DEMO_TABLES = {
        "med_checkin_records",
        "in_app_messages",
        "drug_order_items",
        "drug_orders",
        "prescription_items",
        "prescriptions",
        "consultation_records",
        "payments",
        "appointments",
        "chat_rounds",
        "messages",
        "report_interpretations",
        "health_profile_allergies",
        "health_profiles",
        "conversations",
        "patients",
        "schedules"
    };

    private static final List<String> RESET_STEPS =
            List.of("freeze", "clear_redis", "truncate_tables", "reseed", "rebuild_redis", "unfreeze", "assert");

    private final Contracts contracts;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final SlotAccounting slotAccounting;
    private final ScheduleMapper scheduleMapper;
    private final Driver neo4jDriver;
    private final DemoFreezeGate freezeGate;

    // 演示重置三重保护之一：env 开关（默认 false）
    @Value("${zhiyu.demo.reset-enabled:false}")
    private boolean resetEnabled;

    // 三重保护之三：进程内互斥锁，与本地单实例拓扑匹配（ADR-0022）
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 执行重置。三重保护不满足直接抛 {@link ApiException}；中途失败返回 {@link ResetResult}（success=false）。
     * 调用方据 success 映射 HTTP 200/503，保持错误体形状与成功体一致以便演示者观察步骤清单。
     */
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

    private void runStep(String step, List<String> completed) {
        switch (step) {
            case "freeze" -> {
                // 冻结 C 端入口；freezeGate 已冻结说明上一次重置中途失败未解冻，幂等续跑
                freezeGate.freeze();
            }
            case "clear_redis" -> clearRedisDemoKeys();
            case "truncate_tables" -> truncateDemoTables();
            case "reseed" -> reseed();
            case "rebuild_redis" -> rebuildRedisCounts();
            case "unfreeze" -> freezeGate.unfreeze();
            case "assert" -> assertConsistency();
            default -> throw new IllegalStateException("未知重置步骤: " + step);
        }
    }

    /** 清 Redis 演示键：全部号源计数 + 知识源开关键。 */
    private void clearRedisDemoKeys() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(SlotKeys.keyPattern())
                .count(100)
                .build();
        List<String> keys = new ArrayList<>();
        // 通过 execute 拿到原始 RedisConnection 跑 SCAN，避免 StringRedisTemplate 顶层无 scan API
        redis.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Redis SCAN 失败", e);
            }
            return null;
        });
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(contracts.demoArsenal().knowledgeSourceRedisKey());
    }

    /** 清 PG 演示业务表：TRUNCATE CASCADE 按外键逆序。保留 staff_users 与参考数据。 */
    private void truncateDemoTables() {
        // RESTART IDENTITY 让 schedules 等表序列归零，重灌 seed 显式 id 从 1 起不撞旧序列
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", DEMO_TABLES) + " RESTART IDENTITY CASCADE");
    }

    /** 重灌幂等 seed.sql（含 schedules 动态日期段）。ON CONFLICT DO NOTHING 在空表上正常插入。 */
    private void reseed() {
        // classpath:seed.sql 由 spring.sql.init 在启动期执行，重置复用同一脚本保证一致
        org.springframework.core.io.ClassPathResource seed =
                new org.springframework.core.io.ClassPathResource("seed.sql");
        try (java.sql.Connection con = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(con, new EncodedResource(seed, StandardCharsets.UTF_8));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("重灌 seed.sql 失败", e);
        }
    }

    /** 重建 Redis 号源计数：遍历重灌后的 schedules，经 SlotAccounting 写回 remaining_slots。 */
    private void rebuildRedisCounts() {
        List<Schedule> schedules = scheduleMapper.selectList(null);
        // 号源只经 SlotAccounting（ArchUnit 强制）：每个排班独立初始化，事务体只做 Redis 写
        for (Schedule s : schedules) {
            slotAccounting.withInitialization(init -> init.init(s.getId(), s.getRemainingSlots()));
        }
    }

    /**
     * 一致性断言：每个 Redis 号源计数 == PG remaining_slots，且 total-remaining == 有效挂号数（重灌后应为 0）；
     * 只读断言 pgvector 知识块数与 Neo4j 各节点数达基线。不等则抛异常（保持冻结）。
     */
    /**
     * 一致性断言（票 25 checklist 第 4 条）：每个 Redis 号源计数 == PG remaining_slots，
     * 且 sum(total_slots - remaining_slots) == 重灌后有效挂号数（重灌后均应为 0）；
     * 只读断言 pgvector 知识块数与 Neo4j 各节点数达基线。不等则抛异常（保持冻结）。
     */
    private void assertConsistency() {
        List<Schedule> schedules = scheduleMapper.selectList(null);
        long slotConsumed = 0;
        for (Schedule s : schedules) {
            String redisVal = redis.opsForValue().get(SlotKeys.key(s.getId()));
            int redisRemaining = redisVal == null ? -1 : Integer.parseInt(redisVal);
            if (redisRemaining != s.getRemainingSlots()) {
                throw new IllegalStateException("号源计数不一致 scheduleId=" + s.getId() + " redis=" + redisRemaining + " pg="
                        + s.getRemainingSlots());
            }
            slotConsumed += (s.getTotalSlots() - s.getRemainingSlots());
        }
        // 票 25 checklist 第 4 条核心不变量：号源消耗量 == 有效挂号数（重灌后均为 0）
        Long effectiveAppointments =
                jdbc.queryForObject("SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'", Long.class);
        long effective = effectiveAppointments == null ? 0 : effectiveAppointments;
        if (slotConsumed != effective) {
            throw new IllegalStateException("号源消耗与有效挂号数不一致 consumed=" + slotConsumed + " appointments=" + effective);
        }
        assertKnowledgeBaselines();
    }

    /** 只读断言 pgvector 知识块数与 Neo4j 节点数达契约基线（不写）。 */
    private void assertKnowledgeBaselines() {
        Contracts.DemoArsenal demo = contracts.demoArsenal();
        Long chunks = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Long.class);
        if (chunks == null
                || chunks != demo.knowledgeBaselines().get("knowledge_chunks").longValue()) {
            throw new IllegalStateException("knowledge_chunks 基线断言失败: actual=" + chunks);
        }
        SessionConfig readOnly =
                SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build();
        try (Session session = neo4jDriver.session(readOnly)) {
            assertNeo4jCount(session, "Symptom", demo.knowledgeBaselines().get("neo4j_symptoms"));
            assertNeo4jCount(session, "Disease", demo.knowledgeBaselines().get("neo4j_diseases"));
            assertNeo4jCount(session, "Department", demo.knowledgeBaselines().get("neo4j_departments"));
            assertNeo4jCount(session, "Medication", demo.knowledgeBaselines().get("neo4j_medications"));
            assertNeo4jCount(
                    session, "Contraindication", demo.knowledgeBaselines().get("neo4j_contraindications"));
        }
    }

    private void assertNeo4jCount(Session session, String label, int expected) {
        long actual = session.executeRead(tx -> tx.run("MATCH (n:" + label + ") RETURN count(n) AS c")
                .single()
                .get("c")
                .asLong());
        if (actual != expected) {
            throw new IllegalStateException("Neo4j " + label + " 基线断言失败: actual=" + actual + " expected=" + expected);
        }
    }

    private List<String> pendingSteps(String failedStep) {
        int idx = RESET_STEPS.indexOf(failedStep);
        return RESET_STEPS.subList(idx, RESET_STEPS.size());
    }

    /** 重置结果（成功与失败统一形状，失败时 frozen_after=true 供演示者判断是否需重跑）。 */
    public record ResetResult(
            boolean success,
            List<String> completedSteps,
            String failedStep,
            List<String> pendingSteps,
            boolean frozenAfter,
            Map<String, String> assertions) {
        public ResetResult {
            completedSteps = List.copyOf(completedSteps);
            pendingSteps = List.copyOf(pendingSteps);
            assertions = assertions == null ? Map.of() : Map.copyOf(assertions);
        }
    }
}
