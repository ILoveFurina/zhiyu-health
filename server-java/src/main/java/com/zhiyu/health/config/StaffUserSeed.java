package com.zhiyu.health.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.StaffUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * staff_users 幂等 seed：口令散列必须走代码（seed.sql 无法计算 BCrypt），
 * 密码从 .env 注入（SEED_ADMIN_PASSWORD / SEED_DOCTOR_PASSWORD / SEED_DOCTOR2_PASSWORD），缺省则跳过，不入库。
 */
@Component
public class StaffUserSeed implements ApplicationRunner {

    private static final long DEMO_DOCTOR_ID = 1L;
    private static final long DEMO_DOCTOR2_ID = 2L;

    private final StaffUserMapper staffUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final String doctorPassword;
    private final String doctor2Password;

    public StaffUserSeed(
            StaffUserMapper staffUserMapper,
            PasswordEncoder passwordEncoder,
            @Value("${zhiyu.seed.admin-password:}") String adminPassword,
            @Value("${zhiyu.seed.doctor-password:}") String doctorPassword,
            @Value("${zhiyu.seed.doctor2-password:}") String doctor2Password) {
        this.staffUserMapper = staffUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.doctorPassword = doctorPassword;
        this.doctor2Password = doctor2Password;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfAbsent("admin", adminPassword, StaffUser.ROLE_ADMIN, null);
        seedIfAbsent("doctor.lin", doctorPassword, StaffUser.ROLE_DOCTOR, DEMO_DOCTOR_ID);
        // 第二个演示医生账号（票 26）：绑周安宁（id=2，心血管内科副主任医师），供禁忌拦截支线对照演示
        seedIfAbsent("doctor.zhou", doctor2Password, StaffUser.ROLE_DOCTOR, DEMO_DOCTOR2_ID);
    }

    private void seedIfAbsent(String username, String password, String role, Long doctorId) {
        if (!StringUtils.hasText(password)) {
            return;
        }
        boolean exists = staffUserMapper.selectCount(
                new QueryWrapper<StaffUser>().eq("username", username)) > 0;
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
