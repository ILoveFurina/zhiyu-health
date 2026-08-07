package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zhiyu.health.entity.OnlineConsultation;
import com.zhiyu.health.mapper.OnlineConsultationMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import org.apache.ibatis.exceptions.PersistenceException;
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
import org.postgresql.util.PSQLException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 在线问诊条件更新与部分唯一索引的真实 PostgreSQL 集成测试（票 54，Spec 0003）。
 * 默认构建跳过；显式开启：
 * <pre>mvn -f server-java/pom.xml test -Dpg.it=true -Dtest=OnlineConsultationPgIntegrationTest</pre>
 * 需要环境变量 DATABASE_JDBC_URL / DATABASE_USER / POSTGRES_PASSWORD 指向一次性库
 * （测试会执行 schema.sql 全量 DDL，并写/删 9900xx 号段的固定 fixtures）。
 */
@EnabledIfSystemProperty(named = "pg.it", matches = "true")
class OnlineConsultationPgIntegrationTest {

    private static final long HOSPITAL = 990001L;
    private static final long CAMPUS = 990001L;
    private static final long STD_DEPT_RESPIRATORY = 990001L;
    private static final long STD_DEPT_OTHER = 990002L;
    private static final long DEPT_CATEGORY = 990001L;
    private static final long DEPARTMENT = 990001L;
    private static final long DOCTOR_A = 990001L;
    private static final long DOCTOR_B = 990002L;
    private static final long PATIENT = 990001L;
    private static final long PROFILE = 990001L;
    private static final long DRAFT = 990001L;

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
        configuration.addMapper(OnlineConsultationMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void cleanTicketTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM online_consultation_messages WHERE consultation_id IN"
                    + " (SELECT id FROM online_consultations WHERE patient_id = " + PATIENT + ")");
            statement.execute("DELETE FROM online_consultations WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM preconsultation_drafts WHERE patient_id = " + PATIENT);
            statement.execute("INSERT INTO preconsultation_drafts(id, patient_id, health_profile_id, status)"
                    + " VALUES (" + DRAFT + ", " + PATIENT + ", " + PROFILE + ", 'SUBMITTED')");
        }
    }

    /**
     * 共享演示库保护：9900xx 号段 fixtures 全部回收，避免 IT 医院/医生出现在 B 端目录与接诊池。
     * 与 insertFixtures 同序逆删；删除失败不影响已完成的断言，故只在最后执行一次。
     */
    @AfterAll
    static void removeFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM online_consultation_messages WHERE consultation_id IN"
                    + " (SELECT id FROM online_consultations WHERE patient_id = " + PATIENT + ")");
            statement.execute("DELETE FROM online_consultations WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM preconsultation_drafts WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM health_profiles WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM patients WHERE id = " + PATIENT);
            statement.execute("DELETE FROM doctors WHERE id IN (" + DOCTOR_A + ", " + DOCTOR_B + ")");
            statement.execute("DELETE FROM departments WHERE id = " + DEPARTMENT);
            statement.execute("DELETE FROM department_categories WHERE id = " + DEPT_CATEGORY);
            statement.execute("DELETE FROM standard_departments WHERE id IN (" + STD_DEPT_RESPIRATORY + ", "
                    + STD_DEPT_OTHER + ")");
            statement.execute("DELETE FROM hospital_campuses WHERE id = " + CAMPUS);
            statement.execute("DELETE FROM hospitals WHERE id = " + HOSPITAL);
        }
    }

    /** 原子接受：同一 WAITING_DOCTOR 单第二次条件更新 affected rows = 0（Spec 0003）。 */
    @Test
    void acceptAffectedRowsOnlyOnceForSameWaitingConsultation() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OnlineConsultationMapper mapper = session.getMapper(OnlineConsultationMapper.class);
            OnlineConsultation consultation = waitingConsultation();
            mapper.insert(consultation);

            assertThat(mapper.accept(consultation.getId(), DOCTOR_A, "WAITING_DOCTOR", "IN_PROGRESS"))
                    .isEqualTo(1);
            // 另一医生竞争同一行：状态已推进，条件更新不再命中
            assertThat(mapper.accept(consultation.getId(), DOCTOR_B, "WAITING_DOCTOR", "IN_PROGRESS"))
                    .isEqualTo(0);
            OnlineConsultation accepted = mapper.selectDetailedById(consultation.getId());
            assertThat(accepted.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(accepted.getDoctorId()).isEqualTo(DOCTOR_A);
            assertThat(accepted.getAcceptedAt()).isNotNull();
        }
    }

    /** 部分唯一索引：同档案第二条活跃单被 DB 拒绝；原单取消后（终态不占索引）允许新 WAITING。 */
    @Test
    void activeProfilePartialUniqueIndexRejectsSecondWaitingButAllowsAfterCancel() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OnlineConsultationMapper mapper = session.getMapper(OnlineConsultationMapper.class);
            OnlineConsultation first = waitingConsultation();
            mapper.insert(first);

            assertThatThrownBy(() -> mapper.insert(waitingConsultation()))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("uq_online_consultations_active_profile");

            assertThat(mapper.cancel(first.getId(), PATIENT, "WAITING_DOCTOR", "CANCELLED"))
                    .isEqualTo(1);
            OnlineConsultation fresh = waitingConsultation();
            mapper.insert(fresh);
            assertThat(fresh.getId()).isNotNull().isNotEqualTo(first.getId());
        }
    }

    /** 惰性失效收敛：过期 WAITING 被收敛为 EXPIRED，且过期单的条件接受命中 0 行。 */
    @Test
    void expireOverdueConvertsPastWaitingAndBlocksAccept() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OnlineConsultationMapper mapper = session.getMapper(OnlineConsultationMapper.class);
            OnlineConsultation overdue = waitingConsultation();
            overdue.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
            mapper.insert(overdue);

            // 惰性收敛是全表 sweep：共享演示库可能已有其他过期 WAITING 行，断言至少命中本例行
            assertThat(mapper.expireOverdue("WAITING_DOCTOR", "EXPIRED")).isGreaterThanOrEqualTo(1);
            assertThat(mapper.selectDetailedById(overdue.getId()).getStatus()).isEqualTo("EXPIRED");
            // 条件更新自带 expires_at > now()：即使状态未被收敛，过期单也不可被接受
            assertThat(mapper.accept(overdue.getId(), DOCTOR_A, "WAITING_DOCTOR", "IN_PROGRESS"))
                    .isEqualTo(0);
        }
    }

    private static OnlineConsultation waitingConsultation() {
        OnlineConsultation consultation = new OnlineConsultation();
        consultation.setPatientId(PATIENT);
        consultation.setHealthProfileId(PROFILE);
        consultation.setDraftId(DRAFT);
        consultation.setStandardDepartmentId(STD_DEPT_RESPIRATORY);
        consultation.setChiefComplaint("PG 集成测试主诉");
        consultation.setPresentIllness("PG 集成测试现病史");
        consultation.setSummaryDisclaimer("仅供参考，不替代医生诊断");
        consultation.setStatus("WAITING_DOCTOR");
        consultation.setExpiresAt(OffsetDateTime.now().plusSeconds(600));
        return consultation;
    }

    /** 固定 9900xx 号段 fixtures：先删后插保证可重复执行；命名含 IT 前缀避免撞 seed 唯一约束。 */
    private static void insertFixtures(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM online_consultation_messages WHERE consultation_id IN"
                    + " (SELECT id FROM online_consultations WHERE patient_id = " + PATIENT + ")");
            statement.execute("DELETE FROM online_consultations WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM preconsultation_drafts WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM health_profiles WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM patients WHERE id = " + PATIENT);
            statement.execute("DELETE FROM doctors WHERE id IN (" + DOCTOR_A + ", " + DOCTOR_B + ")");
            statement.execute("DELETE FROM departments WHERE id = " + DEPARTMENT);
            statement.execute("DELETE FROM department_categories WHERE id = " + DEPT_CATEGORY);
            statement.execute("DELETE FROM standard_departments WHERE id IN (" + STD_DEPT_RESPIRATORY + ", "
                    + STD_DEPT_OTHER + ")");
            statement.execute("DELETE FROM hospital_campuses WHERE id = " + CAMPUS);
            statement.execute("DELETE FROM hospitals WHERE id = " + HOSPITAL);

            statement.execute("INSERT INTO hospitals(id, name, level) VALUES (" + HOSPITAL + ", 'IT集成测试医院', '三甲')");
            statement.execute("INSERT INTO hospital_campuses(id, hospital_id, name, city_code, city_name, address)"
                    + " VALUES (" + CAMPUS + ", " + HOSPITAL + ", 'IT总院区', '410100', '郑州', '测试路 1 号')");
            statement.execute("INSERT INTO standard_departments(id, category, name) VALUES ("
                    + STD_DEPT_RESPIRATORY + ", 'IT内科', 'IT呼吸内科'), ("
                    + STD_DEPT_OTHER + ", 'IT外科', 'IT普外科')");
            statement.execute("INSERT INTO department_categories(id, hospital_id, name) VALUES (" + DEPT_CATEGORY + ", "
                    + HOSPITAL + ", 'IT门诊')");
            statement.execute("INSERT INTO departments(id, campus_id, category_id, standard_department_id, name)"
                    + " VALUES (" + DEPARTMENT + ", " + CAMPUS + ", " + DEPT_CATEGORY + ", "
                    + STD_DEPT_RESPIRATORY + ", 'IT呼吸内科门诊')");
            statement.execute("INSERT INTO doctors(id, department_id, name, title) VALUES ("
                    + DOCTOR_A + ", " + DEPARTMENT + ", 'IT医生甲', '主任医师'), ("
                    + DOCTOR_B + ", " + DEPARTMENT + ", 'IT医生乙', '副主任医师')");
            statement.execute("INSERT INTO patients(id, nickname) VALUES (" + PATIENT + ", 'it患者甲')");
            statement.execute("INSERT INTO health_profiles(id, patient_id, display_name, gender, birth_date,"
                    + " relationship, active) VALUES (" + PROFILE + ", " + PATIENT
                    + ", '本人', 'MALE', '1990-01-01', 'SELF', FALSE)");
        }
    }
}
