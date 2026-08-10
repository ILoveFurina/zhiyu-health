package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zhiyu.health.entity.consultation.ConsultationRecord;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.mapper.consultation.ConsultationRecordMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 处方/接诊记录双来源约束与审核、库存并发语义的真实 PostgreSQL 集成测试（票 56，Spec 0003）；
 * 票 88 增补：一院区一药房唯一、处方活跃订单部分唯一索引与履约条件更新并发。
 * 默认构建跳过；显式开启：
 * <pre>mvn -f server-java/pom.xml test -Dpg.it=true -Dtest=PrescriptionSourcePgIntegrationTest</pre>
 * 需要环境变量 DATABASE_JDBC_URL / DATABASE_USER / POSTGRES_PASSWORD 指向一次性库
 * （测试会执行 schema.sql 全量 DDL，并写/删 9900xx 号段的固定 fixtures）。
 */
@EnabledIfSystemProperty(named = "pg.it", matches = "true")
class PrescriptionSourcePgIntegrationTest {

    private static final long HOSPITAL = 990001L;
    private static final long CAMPUS = 990001L;
    private static final long STD_DEPT_RESPIRATORY = 990001L;
    private static final long DEPT_CATEGORY = 990001L;
    private static final long DEPARTMENT = 990001L;
    private static final long DOCTOR = 990001L;
    private static final long PATIENT = 990001L;
    private static final long PROFILE = 990001L;
    private static final long STAFF = 990001L;
    private static final long SCHEDULE_A = 990001L;
    private static final long SCHEDULE_B = 990002L;
    private static final long APPOINTMENT_A = 990001L;
    private static final long APPOINTMENT_B = 990002L;
    private static final long DRAFT = 990001L;
    private static final long CONSULTATION = 990001L;
    private static final long MEDICATION = 990001L;
    private static final long PHARMACY = 990001L;
    private static final long PHARMACY_MEDICATION = 990001L;
    private static final int INITIAL_STOCK = 10;

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
        configuration.addMapper(PrescriptionMapper.class);
        configuration.addMapper(ConsultationRecordMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void cleanTicketTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            deleteTicketRows(statement);
            // 每个用例从同一库存基线出发，避免相互影响（票 88：库存在药房药品关系上，各院区独立）
            statement.execute(
                    "UPDATE pharmacy_medications SET stock = " + INITIAL_STOCK + " WHERE id = " + PHARMACY_MEDICATION);
        }
    }

    /**
     * 共享演示库保护：9900xx 号段 fixtures 全部回收，避免 IT 医院/医生/药品出现在 B 端目录。
     * 与 insertFixtures 同序逆删；删除失败不影响已完成的断言，故只在最后执行一次。
     */
    @AfterAll
    static void removeFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            deleteTicketRows(statement);
            statement.execute("DELETE FROM online_consultations WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM preconsultation_drafts WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM appointments WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM schedules WHERE id IN (" + SCHEDULE_A + ", " + SCHEDULE_B + ")");
            statement.execute("DELETE FROM pharmacy_medications WHERE id = " + PHARMACY_MEDICATION);
            statement.execute("DELETE FROM medications WHERE id = " + MEDICATION);
            statement.execute("DELETE FROM campus_pharmacies WHERE id = " + PHARMACY);
            statement.execute("DELETE FROM staff_users WHERE id = " + STAFF);
            statement.execute("DELETE FROM health_profiles WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM patients WHERE id = " + PATIENT);
            statement.execute("DELETE FROM doctors WHERE id = " + DOCTOR);
            statement.execute("DELETE FROM departments WHERE id = " + DEPARTMENT);
            statement.execute("DELETE FROM department_categories WHERE id = " + DEPT_CATEGORY);
            statement.execute("DELETE FROM standard_departments WHERE id = " + STD_DEPT_RESPIRATORY);
            statement.execute("DELETE FROM hospital_campuses WHERE id = " + CAMPUS);
            statement.execute("DELETE FROM hospitals WHERE id = " + HOSPITAL);
        }
    }

    /** 来源 XOR：两外键同填或同空都被 ck_prescriptions_source / ck_consultation_records_source 拒绝。 */
    @Test
    void sourceXorCheckRejectsBothFilledAndBothEmpty() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            PrescriptionMapper prescriptions = session.getMapper(PrescriptionMapper.class);
            Prescription bothFilled = appointmentPrescription();
            bothFilled.setOnlineConsultationId(CONSULTATION);
            assertThatThrownBy(() -> prescriptions.insert(bothFilled))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("ck_prescriptions_source");
            Prescription bothEmpty = new Prescription();
            bothEmpty.setDoctorId(DOCTOR);
            bothEmpty.setSourceCampusId(CAMPUS);
            bothEmpty.setStatus("PENDING");
            assertThatThrownBy(() -> prescriptions.insert(bothEmpty))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("ck_prescriptions_source");

            ConsultationRecordMapper records = session.getMapper(ConsultationRecordMapper.class);
            ConsultationRecord recordBothFilled = onlineRecord();
            recordBothFilled.setAppointmentId(APPOINTMENT_A);
            assertThatThrownBy(() -> records.insert(recordBothFilled))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("ck_consultation_records_source");
            ConsultationRecord recordBothEmpty = new ConsultationRecord();
            recordBothEmpty.setDoctorId(DOCTOR);
            recordBothEmpty.setDiagnosis("IT 诊断");
            recordBothEmpty.setAdvice("IT 医嘱");
            assertThatThrownBy(() -> records.insert(recordBothEmpty))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("ck_consultation_records_source");
        }
    }

    /** 各来源一对一：两列各自 UNIQUE（PG 唯一索引多 NULL 互不冲突），同一线问诊第二张处方被拒。 */
    @Test
    void perSourceUniqueConstraintsKeepOnePrescriptionPerSource() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            PrescriptionMapper prescriptions = session.getMapper(PrescriptionMapper.class);
            prescriptions.insert(appointmentPrescription());
            // 同一挂号单第二张处方：appointment_id UNIQUE 拒绝
            assertThatThrownBy(() -> prescriptions.insert(appointmentPrescription()))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("prescriptions_appointment_id");

            // 另一张挂号单的处方可共存：多行 online_consultation_id = NULL 互不冲突
            Prescription secondAppointment = appointmentPrescription();
            secondAppointment.setAppointmentId(APPOINTMENT_B);
            prescriptions.insert(secondAppointment);
            assertThat(secondAppointment.getId()).isNotNull();

            // 在线问诊来源同样一对一
            prescriptions.insert(onlinePrescription());
            assertThatThrownBy(() -> prescriptions.insert(onlinePrescription()))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("uq_prescriptions_online_consultation");

            ConsultationRecordMapper records = session.getMapper(ConsultationRecordMapper.class);
            records.insert(onlineRecord());
            assertThatThrownBy(() -> records.insert(onlineRecord()))
                    .isInstanceOf(PersistenceException.class)
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("uq_consultation_records_online_consultation");
        }
    }

    /** 审核并发：两请求并发 review 同一 PENDING 处方，条件更新保证恰好一个 affected=1。 */
    @Test
    void concurrentReviewOfSamePendingPrescriptionYieldsExactlyOneWinner() throws Exception {
        long prescriptionId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            Prescription online = onlinePrescription();
            session.getMapper(PrescriptionMapper.class).insert(online);
            prescriptionId = online.getId();
        }

        List<Integer> results = runConcurrently(2, () -> {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                return session.getMapper(PrescriptionMapper.class)
                        .review(prescriptionId, "APPROVED", null, STAFF, "IT 解读", "仅供参考，不替代医生诊断", "PENDING");
            }
        });

        assertThat(results).containsExactlyInAnyOrder(1, 0);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            Prescription reviewed = session.getMapper(PrescriptionMapper.class).selectById(prescriptionId);
            assertThat(reviewed.getStatus()).isEqualTo("APPROVED");
        }
    }

    /**
     * 库存防超卖（票 88：与下单预扣同一语义，库存在 pharmacy_medications 上）：库存只能经
     * stock >= n 条件 UPDATE 预扣，并发下扣减成功次数不得超过基线可供给单数，库存绝不为负。
     * 整单原子扣减与药房行锁由履约阶段（票 88 后续）的 mapper 覆盖，此处钉住 PG 条件更新语义。
     */
    @Test
    void concurrentStockDeductionNeverOversells() throws Exception {
        // 基线 10 件、5 个并发买家各预扣 4 件：恰好 2 个成功（2*4 <= 10 < 3*4）
        List<Integer> results = runConcurrently(5, () -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                return statement.executeUpdate("UPDATE pharmacy_medications SET stock = stock - 4 WHERE id = "
                        + PHARMACY_MEDICATION + " AND stock >= 4");
            }
        });

        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(currentStock()).isEqualTo(INITIAL_STOCK - 8);
    }

    /** 一院区一药房（票 88）：campus_pharmacies.campus_id UNIQUE，同院区第二家药房被 DB 拒绝。 */
    @Test
    void onePharmacyPerCampusUniqueConstraint() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // fixtures 已占用 CAMPUS 院区（PHARMACY），重复插入即违反 UNIQUE —— 插入失败无残留，无需清理
            assertThatThrownBy(() ->
                            statement.execute("INSERT INTO campus_pharmacies(campus_id, display_name, delivery_fee,"
                                    + " estimated_delivery_minutes) VALUES (" + CAMPUS
                                    + ", 'IT重复药房', 5.00, 45)"))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("campus_pharmacies_campus_id_key");
        }
    }

    /**
     * 处方活跃订单唯一（票 88）：uq_drug_orders_active_prescription 部分唯一索引保证同一处方
     * 至多一张未取消/未过期订单，并发重复下单由 DB 兜底；取消/过期后处方可再次下单。
     */
    @Test
    void activePrescriptionOrderPartialUniqueIndex() throws Exception {
        long prescriptionId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            Prescription prescription = appointmentPrescription();
            session.getMapper(PrescriptionMapper.class).insert(prescription);
            prescriptionId = prescription.getId();
        }

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            insertDrugOrder(statement, prescriptionId, "UNPAID");
            // 同一处方第二张活跃订单（无论哪种未完结状态）被部分唯一索引拒绝
            assertThatThrownBy(() -> insertDrugOrder(statement, prescriptionId, "PAID"))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("uq_drug_orders_active_prescription");
            // 已取消/已过期订单不占活跃位：取消后再下一张 UNPAID 订单可共存
            insertDrugOrder(statement, prescriptionId, "CANCELLED");
            long reordered = insertDrugOrder(statement, prescriptionId, "UNPAID");
            assertThat(reordered).isPositive();
        }
    }

    /**
     * 履约并发（票 88，ADR-0035）：两请求并发推进同一 PAID 订单配药，条件更新保证恰好一个
     * affected=1，订单终态只推进一次（与审核并发同一语义，mapper 层 0 行即 409）。
     */
    @Test
    void concurrentFulfillmentTransitionYieldsExactlyOneWinner() throws Exception {
        long orderId;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // OTC 订单（prescription_id NULL）避免与活跃处方唯一索引用例相互干扰
            orderId = insertDrugOrder(statement, null, "PAID");
        }

        List<Integer> results = runConcurrently(2, () -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                return statement.executeUpdate("UPDATE drug_orders SET status = 'DISPENSING', dispensing_at = now()"
                        + " WHERE id = " + orderId + " AND status = 'PAID'");
            }
        });

        assertThat(results).containsExactlyInAnyOrder(1, 0);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT status FROM drug_orders WHERE id = " + orderId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("DISPENSING");
        }
    }

    /**
     * 插入 drug_orders 固定形状行并回读自增 id：只填 NOT NULL 列，取药方式 PICKUP
     * （ck_drug_orders_receiver_snapshot 要求自取单收货信息全空，默认即满足）。
     */
    private static long insertDrugOrder(Statement statement, Long prescriptionId, String status) throws Exception {
        String prescription = prescriptionId == null ? "NULL" : String.valueOf(prescriptionId);
        statement.executeUpdate(
                "INSERT INTO drug_orders(patient_id, prescription_id, pharmacy_id, pickup_method, status,"
                        + " medication_amount, delivery_fee, total_amount, pharmacy_name, hospital_name,"
                        + " campus_name, campus_address, payment_deadline) VALUES (" + PATIENT + ", "
                        + prescription + ", " + PHARMACY + ", 'PICKUP', '" + status
                        + "', 18.50, 0.00, 18.50, 'IT总院区药房', 'IT集成测试医院', 'IT总院区', '测试路 1 号',"
                        + " now() + interval '15 minutes')",
                Statement.RETURN_GENERATED_KEYS);
        try (ResultSet keys = statement.getGeneratedKeys()) {
            assertThat(keys.next()).isTrue();
            return keys.getLong(1);
        }
    }

    /** 并发执行同一操作：统一门闩放行，收集各线程 affected rows。 */
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

    private static int currentStock() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT stock FROM pharmacy_medications WHERE id = " + PHARMACY_MEDICATION)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static Prescription appointmentPrescription() {
        Prescription prescription = new Prescription();
        prescription.setAppointmentId(APPOINTMENT_A);
        prescription.setDoctorId(DOCTOR);
        prescription.setSourceCampusId(CAMPUS);
        prescription.setStatus("PENDING");
        return prescription;
    }

    private static Prescription onlinePrescription() {
        Prescription prescription = new Prescription();
        prescription.setOnlineConsultationId(CONSULTATION);
        prescription.setDoctorId(DOCTOR);
        prescription.setSourceCampusId(CAMPUS);
        prescription.setStatus("PENDING");
        return prescription;
    }

    private static ConsultationRecord onlineRecord() {
        ConsultationRecord record = new ConsultationRecord();
        record.setOnlineConsultationId(CONSULTATION);
        record.setDoctorId(DOCTOR);
        record.setDiagnosis("IT 诊断");
        record.setAdvice("IT 医嘱");
        return record;
    }

    /** 本票写入行统一清场：drug_order_items 随 drug_orders、prescription_items 随 prescriptions ON DELETE CASCADE 联动。 */
    private static void deleteTicketRows(Statement statement) throws Exception {
        // drug_orders 引用 prescriptions（prescription_id），必须先于 prescriptions 删除
        statement.execute("DELETE FROM drug_orders WHERE patient_id = " + PATIENT);
        statement.execute("DELETE FROM prescriptions WHERE doctor_id = " + DOCTOR);
        statement.execute("DELETE FROM consultation_records WHERE doctor_id = " + DOCTOR);
    }

    /** 固定 9900xx 号段 fixtures：先删后插保证可重复执行；命名含 IT 前缀避免撞 seed 唯一约束。 */
    private static void insertFixtures(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            deleteTicketRows(statement);
            statement.execute("DELETE FROM online_consultations WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM preconsultation_drafts WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM appointments WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM schedules WHERE id IN (" + SCHEDULE_A + ", " + SCHEDULE_B + ")");
            statement.execute("DELETE FROM pharmacy_medications WHERE id = " + PHARMACY_MEDICATION);
            statement.execute("DELETE FROM medications WHERE id = " + MEDICATION);
            statement.execute("DELETE FROM campus_pharmacies WHERE id = " + PHARMACY);
            statement.execute("DELETE FROM staff_users WHERE id = " + STAFF);
            statement.execute("DELETE FROM health_profiles WHERE patient_id = " + PATIENT);
            statement.execute("DELETE FROM patients WHERE id = " + PATIENT);
            statement.execute("DELETE FROM doctors WHERE id = " + DOCTOR);
            statement.execute("DELETE FROM departments WHERE id = " + DEPARTMENT);
            statement.execute("DELETE FROM department_categories WHERE id = " + DEPT_CATEGORY);
            statement.execute("DELETE FROM standard_departments WHERE id = " + STD_DEPT_RESPIRATORY);
            statement.execute("DELETE FROM hospital_campuses WHERE id = " + CAMPUS);
            statement.execute("DELETE FROM hospitals WHERE id = " + HOSPITAL);

            statement.execute("INSERT INTO hospitals(id, name, level) VALUES (" + HOSPITAL + ", 'IT集成测试医院', '三甲')");
            statement.execute("INSERT INTO hospital_campuses(id, hospital_id, name, city_code, city_name, address)"
                    + " VALUES (" + CAMPUS + ", " + HOSPITAL + ", 'IT总院区', '410100', '郑州', '测试路 1 号')");
            statement.execute("INSERT INTO standard_departments(id, category, name) VALUES (" + STD_DEPT_RESPIRATORY
                    + ", 'IT内科', 'IT呼吸内科')");
            statement.execute("INSERT INTO department_categories(id, hospital_id, name) VALUES (" + DEPT_CATEGORY + ", "
                    + HOSPITAL + ", 'IT门诊')");
            statement.execute("INSERT INTO departments(id, campus_id, category_id, standard_department_id, name)"
                    + " VALUES (" + DEPARTMENT + ", " + CAMPUS + ", " + DEPT_CATEGORY + ", "
                    + STD_DEPT_RESPIRATORY + ", 'IT呼吸内科门诊')");
            statement.execute("INSERT INTO doctors(id, department_id, name, title) VALUES (" + DOCTOR + ", "
                    + DEPARTMENT + ", 'IT医生甲', '主任医师')");
            statement.execute(
                    "INSERT INTO staff_users(id, username, role) VALUES (" + STAFF + ", 'it-admin-990001', 'ADMIN')");
            statement.execute("INSERT INTO patients(id, nickname) VALUES (" + PATIENT + ", 'it患者甲')");
            statement.execute("INSERT INTO health_profiles(id, patient_id, display_name, gender, birth_date,"
                    + " relationship, active) VALUES (" + PROFILE + ", " + PATIENT
                    + ", '本人', 'MALE', '1990-01-01', 'SELF', FALSE)");
            statement.execute("INSERT INTO schedules(id, doctor_id, schedule_date, time_slot, total_slots,"
                    + " remaining_slots) VALUES (" + SCHEDULE_A + ", " + DOCTOR + ", '2026-08-10', '上午', 10, 10), ("
                    + SCHEDULE_B + ", " + DOCTOR + ", '2026-08-11', '下午', 10, 10)");
            statement.execute("INSERT INTO appointments(id, patient_id, health_profile_id, schedule_id,"
                    + " sequence_number, registration_fee, status) VALUES (" + APPOINTMENT_A + ", " + PATIENT + ", "
                    + PROFILE + ", " + SCHEDULE_A + ", 1, 30.00, 'VISITED'), (" + APPOINTMENT_B + ", " + PATIENT + ", "
                    + PROFILE + ", " + SCHEDULE_B + ", 1, 30.00, 'VISITED')");
            statement.execute("INSERT INTO preconsultation_drafts(id, patient_id, health_profile_id, status)"
                    + " VALUES (" + DRAFT + ", " + PATIENT + ", " + PROFILE + ", 'SUBMITTED')");
            statement.execute("INSERT INTO online_consultations(id, patient_id, health_profile_id, draft_id,"
                    + " standard_department_id, doctor_id, chief_complaint, present_illness, summary_disclaimer,"
                    + " status, expires_at, completed_at) VALUES (" + CONSULTATION + ", " + PATIENT + ", " + PROFILE
                    + ", " + DRAFT + ", " + STD_DEPT_RESPIRATORY + ", " + DOCTOR
                    + ", 'IT 主诉', 'IT 现病史', '仅供参考，不替代医生诊断', 'COMPLETED',"
                    + " now() + interval '1 hour', now())");
            statement.execute("INSERT INTO medications(id, name, generic_name, specification, instructions)"
                    + " VALUES (" + MEDICATION + ", 'IT测试药品990001', 'IT通用名', '0.25g*24粒', '口服')");
            statement.execute("INSERT INTO campus_pharmacies(id, campus_id, display_name, delivery_fee,"
                    + " estimated_delivery_minutes) VALUES (" + PHARMACY + ", " + CAMPUS
                    + ", 'IT总院区药房', 5.00, 45)");
            statement.execute("INSERT INTO pharmacy_medications(id, pharmacy_id, medication_id, price, stock)"
                    + " VALUES (" + PHARMACY_MEDICATION + ", " + PHARMACY + ", " + MEDICATION + ", 18.50, "
                    + INITIAL_STOCK + ")");
        }
    }
}
