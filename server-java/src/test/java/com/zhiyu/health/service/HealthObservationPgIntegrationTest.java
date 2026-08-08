package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zhiyu.health.entity.HealthObservation;
import com.zhiyu.health.mapper.HealthObservationMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 健康观测槽位唯一与状态机并发语义的真实 PostgreSQL 集成测试（票 61，ADR-0031）。
 * 默认构建跳过；显式开启：
 * <pre>mvn -f server-java/pom.xml test -Dpg.it=true -Dtest=HealthObservationPgIntegrationTest</pre>
 * 需要环境变量 DATABASE_JDBC_URL / DATABASE_USER / POSTGRES_PASSWORD 指向一次性库
 * （测试会执行 schema.sql 全量 DDL，并写/删 9900xx 号段的固定 fixtures）。
 */
@EnabledIfSystemProperty(named = "pg.it", matches = "true")
class HealthObservationPgIntegrationTest {

    private static final long PATIENT = 990061L;
    private static final long PROFILE = 990061L;
    private static final long REPORT = 990061L;
    private static final LocalDate SLOT_DATE = LocalDate.parse("2026-05-20");

    private static PGSimpleDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv("DATABASE_JDBC_URL");
        assertThat(url).as("DATABASE_JDBC_URL 未配置：PG 集成测试需要指向一次性库的 JDBC 地址").isNotBlank();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(System.getenv("DATABASE_USER"));
        dataSource.setPassword(System.getenv("POSTGRES_PASSWORD"));
        try (Connection connection = dataSource.getConnection()) {
            // 与应用启动同一入口执行全量 DDL（ScriptUtils 按 ';' 切分，schema.sql 为此设计）
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            insertFixtures(connection);
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("pg-it", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HealthObservationMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void cleanObservations() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM health_observations WHERE health_profile_id = " + PROFILE);
        }
    }

    /** 共享演示库保护：9900xx 号段 fixtures 全部回收，避免 IT 数据出现在演示库。 */
    @AfterAll
    static void removeFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM health_observations WHERE health_profile_id = " + PROFILE);
            statement.execute("DELETE FROM report_interpretations WHERE id = " + REPORT);
            statement.execute("DELETE FROM health_profiles WHERE id = " + PROFILE);
            statement.execute("DELETE FROM patients WHERE id = " + PATIENT);
        }
    }

    /** 每日当前槽位唯一：同档案同指标同日第二个 current 插入被 ON CONFLICT 吞掉。 */
    @Test
    void currentSlotUniqueSwallowsSecondInsertViaOnConflict() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            HealthObservationMapper observations = session.getMapper(HealthObservationMapper.class);
            assertThat(observations.insertIgnoreSlot(reportAi("FASTING_GLUCOSE", "5.3")))
                    .isEqualTo(1);
            // 禁止先查后改：槽位冲突交给 ON CONFLICT DO NOTHING，影响行数 0 即 DUPLICATE_SLOT
            assertThat(observations.insertIgnoreSlot(reportAi("FASTING_GLUCOSE", "5.4")))
                    .isEqualTo(0);
        }
        assertThat(countRows("current AND metric_code = 'FASTING_GLUCOSE'")).isEqualTo(1);
        // 旧值保持，未被后者覆盖
        assertThat(queryValue()).isEqualByComparingTo(new BigDecimal("5.3"));
    }

    /** 并发纠错：条件 UPDATE 收敛，恰好一个线程赢得抢占，最终槽位恰好一条 current 记录。 */
    @Test
    void concurrentCorrectYieldsExactlyOneCurrentRow() throws Exception {
        long observationId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            HealthObservation seed = reportAi("WEIGHT", "58.5");
            session.getMapper(HealthObservationMapper.class).insertIgnoreSlot(seed);
            // insertIgnoreSlot 不回写 id，直查取出
            observationId = queryId("WEIGHT");
        }

        List<Integer> results = runConcurrently(2, () -> {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                HealthObservationMapper observations = session.getMapper(HealthObservationMapper.class);
                int affected = observations.supersede(observationId, "SUPERSEDED", "UNVERIFIED", "USER_CONFIRMED");
                if (affected == 1) {
                    HealthObservation correction = reportAi("WEIGHT", "57.9");
                    correction.setSourceType("USER_CORRECTION");
                    correction.setVerificationStatus("USER_CONFIRMED");
                    correction.setSupersedesId(observationId);
                    observations.insert(correction);
                }
                return affected;
            }
        });

        assertThat(results).containsExactlyInAnyOrder(1, 0);
        assertThat(countRows("current AND metric_code = 'WEIGHT'")).isEqualTo(1);
        assertThat(countRows("verification_status = 'SUPERSEDED' AND metric_code = 'WEIGHT'"))
                .isEqualTo(1);
        assertThat(countRows("source_type = 'USER_CORRECTION' AND metric_code = 'WEIGHT'"))
                .isEqualTo(1);
    }

    /** REJECTED 保持 current 占槽：同槽位重复上传（再 INSERT REPORT_AI 同日期）被槽位唯一阻止复活。 */
    @Test
    void rejectedRowKeepsSlotAndBlocksReuploadRevival() throws Exception {
        long observationId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            HealthObservationMapper observations = session.getMapper(HealthObservationMapper.class);
            observations.insertIgnoreSlot(reportAi("TOTAL_CHOLESTEROL", "4.5"));
            observationId = queryId("TOTAL_CHOLESTEROL");
            assertThat(observations.reject(observationId, "REJECTED", "UNVERIFIED", "USER_CONFIRMED"))
                    .isEqualTo(1);
            // 重复上传同日期同指标：被部分唯一索引吞掉，已排除值不复活
            assertThat(observations.insertIgnoreSlot(reportAi("TOTAL_CHOLESTEROL", "4.6")))
                    .isEqualTo(0);
        }
        assertThat(countRows("current AND metric_code = 'TOTAL_CHOLESTEROL'")).isEqualTo(1);
        assertThat(countRows("current AND metric_code = 'TOTAL_CHOLESTEROL' AND verification_status = 'REJECTED'"))
                .isEqualTo(1);
    }

    private HealthObservation reportAi(String metricCode, String value) {
        HealthObservation observation = new HealthObservation();
        observation.setHealthProfileId(PROFILE);
        observation.setReportInterpretationId(REPORT);
        observation.setMetricCode(metricCode);
        observation.setValueNumeric(new BigDecimal(value));
        observation.setUnit(
                "TOTAL_CHOLESTEROL".equals(metricCode) || "FASTING_GLUCOSE".equals(metricCode) ? "mmol/L" : "kg");
        observation.setObservedOn(SLOT_DATE);
        observation.setSourceType("REPORT_AI");
        observation.setVerificationStatus("UNVERIFIED");
        observation.setCurrent(true);
        return observation;
    }

    private static List<Integer> runConcurrently(int threads, IOCall call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    gate.await();
                    return call.run();
                }));
            }
            gate.countDown();
            List<Integer> results = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private interface IOCall {
        int run() throws Exception;
    }

    private static int countRows(String where) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT count(*) FROM health_observations WHERE health_profile_id = "
                                + PROFILE + " AND " + where + " AND observed_on = '" + SLOT_DATE + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long queryId(String metricCode) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT id FROM health_observations WHERE health_profile_id = "
                        + PROFILE + " AND metric_code = '" + metricCode + "' ORDER BY id DESC LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static BigDecimal queryValue() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT value_numeric FROM health_observations"
                        + " WHERE health_profile_id = " + PROFILE + " AND metric_code = 'FASTING_GLUCOSE'"
                        + " AND current LIMIT 1")) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    /** 固定 9900xx 号段 fixtures：先删后插保证可重复执行。 */
    private static void insertFixtures(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM health_observations WHERE health_profile_id = " + PROFILE);
            statement.execute("DELETE FROM report_interpretations WHERE id = " + REPORT);
            statement.execute("DELETE FROM health_profiles WHERE id = " + PROFILE);
            statement.execute("DELETE FROM patients WHERE id = " + PATIENT);
            statement.execute("INSERT INTO patients(id, nickname) VALUES (" + PATIENT + ", 'it患者观测')");
            statement.execute("INSERT INTO health_profiles(id, patient_id, display_name, gender, birth_date,"
                    + " relationship, active) VALUES (" + PROFILE + ", " + PATIENT
                    + ", '本人', 'MALE', '1990-01-01', 'SELF', FALSE)");
            statement.execute("INSERT INTO report_interpretations(id, patient_id, health_profile_id, request_id,"
                    + " file_type, file_name, status, disclaimer) VALUES (" + REPORT + ", " + PATIENT + ", " + PROFILE
                    + ", 'it-report-990061', 'IMAGE', 'IT观测报告.png', 'SUCCEEDED', '仅供参考，不替代医生诊断')");
        }
    }
}
