package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import java.sql.Connection;
import java.sql.ResultSet;
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
 * 在线问诊条件更新与部分唯一索引的真实 PostgreSQL 集成测试（票 55，Spec 0003）。
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
    private static final long PROFILE_B = 990002L;
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
            statement.execute("DELETE FROM drug_orders WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM prescriptions WHERE online_consultation_id IN"
                    + " (SELECT id FROM online_consultations WHERE patient_id = " + PATIENT + ")");
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
            statement.execute("DELETE FROM drug_orders WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM prescriptions WHERE online_consultation_id IN"
                    + " (SELECT id FROM online_consultations WHERE patient_id = " + PATIENT + ")");
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

    /** 时长窗惰性收敛（票 86）：到期 IN_PROGRESS 翻 EXPIRED 且系统消息恰好一条；二次 sweep 幂等；未到期不误触。 */
    @Test
    void expireInProgressOverdueFlipsDueOnceWithSingleSystemMessage() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OnlineConsultationMapper mapper = session.getMapper(OnlineConsultationMapper.class);
            OnlineConsultation due = waitingConsultation();
            mapper.insert(due);
            assertThat(mapper.accept(due.getId(), DOCTOR_A, "WAITING_DOCTOR", "IN_PROGRESS"))
                    .isEqualTo(1);
            // accepted_at 拨回 31 分钟前，越过 1800s 时长窗
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("UPDATE online_consultations SET accepted_at = now() - interval '31 minutes'"
                        + " WHERE id = " + due.getId());
            }
            // 同患者另一档案的未到期进行中单：不得误触（同档案受部分唯一索引限制只能一条活跃单）
            OnlineConsultation fresh = waitingConsultation();
            fresh.setHealthProfileId(PROFILE_B);
            mapper.insert(fresh);
            assertThat(mapper.accept(fresh.getId(), DOCTOR_B, "WAITING_DOCTOR", "IN_PROGRESS"))
                    .isEqualTo(1);

            // 全表 sweep：共享演示库可能已有其他到期 IN_PROGRESS 行，断言至少命中本例行
            assertThat(mapper.expireInProgressOverdue(
                            "IN_PROGRESS", "EXPIRED", 1800, "SYSTEM", "text", "问诊时间已到，本次问诊已自动结束"))
                    .isGreaterThanOrEqualTo(1);
            assertThat(mapper.selectDetailedById(due.getId()).getStatus()).isEqualTo("EXPIRED");
            assertThat(mapper.selectDetailedById(fresh.getId()).getStatus()).isEqualTo("IN_PROGRESS");
            // 条件 UPDATE 行守卫：二次 sweep 不再翻行，系统消息不重复
            assertThat(mapper.expireInProgressOverdue(
                            "IN_PROGRESS", "EXPIRED", 1800, "SYSTEM", "text", "问诊时间已到，本次问诊已自动结束"))
                    .isZero();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT sender_type, kind, content"
                            + " FROM online_consultation_messages WHERE consultation_id = " + due.getId())) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("SYSTEM");
                assertThat(rs.getString(2)).isEqualTo("text");
                assertThat(rs.getString(3)).isEqualTo("问诊时间已到，本次问诊已自动结束");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    /** 患者主动结束（票 86）：进行中单条件更新一次成功；待接诊/重复结束均 0 行。 */
    @Test
    void endByPatientTransitionsInProgressOnlyOnce() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OnlineConsultationMapper mapper = session.getMapper(OnlineConsultationMapper.class);
            OnlineConsultation consultation = waitingConsultation();
            mapper.insert(consultation);
            // 待接诊不可结束（应走 cancel）
            assertThat(mapper.endByPatient(consultation.getId(), PATIENT, "IN_PROGRESS", "CANCELLED"))
                    .isZero();
            assertThat(mapper.accept(consultation.getId(), DOCTOR_A, "WAITING_DOCTOR", "IN_PROGRESS"))
                    .isEqualTo(1);
            assertThat(mapper.endByPatient(consultation.getId(), PATIENT, "IN_PROGRESS", "CANCELLED"))
                    .isEqualTo(1);
            OnlineConsultation ended = mapper.selectDetailedById(consultation.getId());
            assertThat(ended.getStatus()).isEqualTo("CANCELLED");
            assertThat(ended.getCancelledAt()).isNotNull();
            // 重复结束与越权 patient_id 都不再命中
            assertThat(mapper.endByPatient(consultation.getId(), PATIENT, "IN_PROGRESS", "CANCELLED"))
                    .isZero();
            assertThat(mapper.endByPatient(consultation.getId(), PATIENT + 100, "IN_PROGRESS", "CANCELLED"))
                    .isZero();
        }
    }

    /** 处方追踪覆盖规则（票 86）：每档案只投影最近一次问诊链路，新问诊发起后旧 REJECTED 卡消失。 */
    @Test
    void prescriptionTrackingProjectsOnlyLatestChainPerProfile() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OnlineConsultationMapper mapper = session.getMapper(OnlineConsultationMapper.class);
            OnlineConsultation completed = waitingConsultation();
            mapper.insert(completed);
            markCompleted(completed.getId());
            insertPrescription(completed.getId(), "REJECTED");

            assertThat(mapper.selectUnresolvedPrescriptionTracking(
                            PATIENT, "COMPLETED", "PENDING", "APPROVED", "REJECTED"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.onlineConsultationId()).isEqualTo(completed.getId());
                        assertThat(row.prescriptionStatus()).isEqualTo("REJECTED");
                        assertThat(row.hasDrugOrder()).isFalse();
                        assertThat(row.doctorName()).isEqualTo("IT医生甲");
                        assertThat(row.departmentName()).isEqualTo("IT呼吸内科门诊");
                    });
            // 同档案发起新问诊（WAITING）：最新链路非 COMPLETED，旧链路的未终结处方不再投影
            mapper.insert(waitingConsultation());
            assertThat(mapper.selectUnresolvedPrescriptionTracking(
                            PATIENT, "COMPLETED", "PENDING", "APPROVED", "REJECTED"))
                    .isEmpty();
        }
    }

    /** 已下单标记（票 86）：APPROVED 处方存在药品订单时 has_drug_order=true，由调用方交接给待支付卡。 */
    @Test
    void prescriptionTrackingFlagsApprovedWithExistingDrugOrder() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OnlineConsultationMapper mapper = session.getMapper(OnlineConsultationMapper.class);
            OnlineConsultation withOrder = waitingConsultation();
            mapper.insert(withOrder);
            markCompleted(withOrder.getId());
            long rxWithOrder = insertPrescription(withOrder.getId(), "APPROVED");
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO drug_orders(patient_id, prescription_id, status, total_amount)"
                        + " VALUES (" + PATIENT + ", " + rxWithOrder + ", 'UNPAID', 10.00)");
            }
            OnlineConsultation withoutOrder = waitingConsultation();
            withoutOrder.setHealthProfileId(PROFILE_B);
            mapper.insert(withoutOrder);
            markCompleted(withoutOrder.getId());
            insertPrescription(withoutOrder.getId(), "APPROVED");

            assertThat(mapper.selectUnresolvedPrescriptionTracking(
                            PATIENT, "COMPLETED", "PENDING", "APPROVED", "REJECTED"))
                    .hasSize(2)
                    .anySatisfy(row -> {
                        assertThat(row.prescriptionId()).isEqualTo(rxWithOrder);
                        assertThat(row.hasDrugOrder()).isTrue();
                    })
                    .anySatisfy(row -> assertThat(row.hasDrugOrder()).isFalse());
        }
    }

    /** 把插入的 WAITING 单直接置为 COMPLETED 终态（投影测试只关心链路形状，不走完整状态机）。 */
    private void markCompleted(long consultationId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE online_consultations SET status = 'COMPLETED', doctor_id = " + DOCTOR_A
                    + ", accepted_at = now(), completed_at = now() WHERE id = " + consultationId);
        }
    }

    /** 插一条处方并返回 id；APPROVED 需满足 ck_prescriptions_patient_visibility（解读+免责声明非空）。 */
    private long insertPrescription(long consultationId, String status) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "INSERT INTO prescriptions(online_consultation_id, doctor_id, status, interpretation,"
                                + " disclaimer) VALUES (" + consultationId + ", " + DOCTOR_A + ", '" + status + "',"
                                + " '用药解读', '仅供参考，不替代医生诊断') RETURNING id")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
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
                    + ", '本人', 'MALE', '1990-01-01', 'SELF', FALSE), (" + PROFILE_B + ", " + PATIENT
                    + ", 'IT家属', 'FEMALE', '1965-01-01', 'MOTHER', FALSE)");
        }
    }
}
