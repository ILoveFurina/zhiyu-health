package com.zhiyu.health.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.StaffUserMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * staff_users 幂等 seed：口令散列必须走代码（seed.sql 无法计算 BCrypt），
 * 密码从 .env 注入（SEED_ADMIN_PASSWORD / SEED_DOCTOR_PASSWORD / SEED_DOCTOR2_PASSWORD / SEED_DOCTORS_PASSWORD），
 * admin/doctor.lin/doctor.zhou 缺省则跳过，不入库；id 3-15 的 13 位医生缺省 doctor123456（票 57 接诊测试约定）。
 */
@Component
public class StaffUserSeed implements ApplicationRunner {

    private static final long DEMO_DOCTOR_ID = 1L;
    private static final long DEMO_DOCTOR2_ID = 2L;

    /**
     * 与 seed.sql doctors 的 id 3-15 一一对应（用户名沿用 doctor.<姓拼音> 约定，
     * 同 doctor.lin/doctor.zhou）；seed.sql 调整医生或 id 时必须同步本表。
     */
    private static final Map<Long, String> EXTRA_DOCTOR_USERNAMES = Map.ofEntries(
            Map.entry(3L, "doctor.chen"),
            Map.entry(4L, "doctor.su"),
            Map.entry(5L, "doctor.li"),
            Map.entry(6L, "doctor.zhao"),
            Map.entry(7L, "doctor.wu"),
            Map.entry(8L, "doctor.sun"),
            Map.entry(9L, "doctor.zheng"),
            Map.entry(10L, "doctor.ma"),
            Map.entry(11L, "doctor.he"),
            Map.entry(12L, "doctor.huang"),
            Map.entry(13L, "doctor.liang"),
            Map.entry(14L, "doctor.feng"),
            Map.entry(15L, "doctor.han"));

    private final StaffUserMapper staffUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final String doctorPassword;
    private final String doctor2Password;
    private final String doctorsPassword;

    public StaffUserSeed(
            StaffUserMapper staffUserMapper,
            PasswordEncoder passwordEncoder,
            @Value("${zhiyu.seed.admin-password:}") String adminPassword,
            @Value("${zhiyu.seed.doctor-password:}") String doctorPassword,
            @Value("${zhiyu.seed.doctor2-password:}") String doctor2Password,
            @Value("${zhiyu.seed.doctors-password:}") String doctorsPassword) {
        this.staffUserMapper = staffUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.doctorPassword = doctorPassword;
        this.doctor2Password = doctor2Password;
        this.doctorsPassword = doctorsPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfAbsent("admin", adminPassword, StaffUser.ROLE_ADMIN, null);
        seedIfAbsent("doctor.lin", doctorPassword, StaffUser.ROLE_DOCTOR, DEMO_DOCTOR_ID);
        // 第二个演示医生账号（票 26）：绑周安宁（id=2，心血管内科副主任医师），供禁忌拦截支线对照演示
        seedIfAbsent("doctor.zhou", doctor2Password, StaffUser.ROLE_DOCTOR, DEMO_DOCTOR2_ID);
        // 其余 13 位医生（票 57）：统一密码，任何医生都可登录接诊台做接诊测试
        EXTRA_DOCTOR_USERNAMES.forEach(
                (doctorId, username) -> seedIfAbsent(username, doctorsPassword, StaffUser.ROLE_DOCTOR, doctorId));
    }

    private void seedIfAbsent(String username, String password, String role, Long doctorId) {
        if (!StringUtils.hasText(password)) {
            return;
        }
        boolean exists = staffUserMapper.selectCount(new QueryWrapper<StaffUser>().eq("username", username)) > 0;
        if (exists) {
            return;
        }
        StaffUser staff = new StaffUser();
        staff.setUsername(username);
        staff.setPasswordHash(passwordEncoder.encode(password));
        staff.setRole(role);
        staff.setDoctorId(doctorId);
        staffUserMapper.insert(staff);
    }
}
