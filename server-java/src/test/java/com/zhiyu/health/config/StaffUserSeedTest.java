package com.zhiyu.health.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.StaffUserMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** staff_users 幂等 seed（票 57）：15 个演示账号全量补种、已存在跳过、密码未配置跳过 */
class StaffUserSeedTest {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);

    private StaffUserSeed seed(
            String adminPassword, String doctorPassword, String doctor2Password, String doctorsPassword) {
        return new StaffUserSeed(
                staffUserMapper, PASSWORD_ENCODER, adminPassword, doctorPassword, doctor2Password, doctorsPassword);
    }

    @Test
    void seedsAllSixteenDemoAccounts() {
        when(staffUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        seed("admin123456", "doctor123456", "doctor123456", "doctor123456").run(null);

        var captor = ArgumentCaptor.forClass(StaffUser.class);
        verify(staffUserMapper, times(16)).insert(captor.capture());
        List<StaffUser> inserted = captor.getAllValues();

        Map<String, StaffUser> byUsername = inserted.stream().collect(Collectors.toMap(StaffUser::getUsername, s -> s));
        assertThat(byUsername.keySet())
                .containsExactlyInAnyOrder(
                        "admin",
                        "doctor.lin",
                        "doctor.zhou",
                        "doctor.chen",
                        "doctor.su",
                        "doctor.li",
                        "doctor.zhao",
                        "doctor.wu",
                        "doctor.sun",
                        "doctor.zheng",
                        "doctor.ma",
                        "doctor.he",
                        "doctor.huang",
                        "doctor.liang",
                        "doctor.feng",
                        "doctor.han");

        assertThat(byUsername.get("admin").getRole()).isEqualTo(StaffUser.ROLE_ADMIN);
        assertThat(byUsername.get("admin").getDoctorId()).isNull();
        assertThat(PASSWORD_ENCODER.matches(
                        "admin123456", byUsername.get("admin").getPasswordHash()))
                .isTrue();

        // 13 位补种医生全部 ROLE_DOCTOR 且绑定各自 doctorId，统一密码可登录
        for (long id = 3; id <= 15; id++) {
            StaffUser staff = byUsername.get(usernameOf(id));
            assertThat(staff).as("doctor id=%d 应有账号", id).isNotNull();
            assertThat(staff.getRole()).isEqualTo(StaffUser.ROLE_DOCTOR);
            assertThat(staff.getDoctorId()).isEqualTo(id);
            assertThat(PASSWORD_ENCODER.matches("doctor123456", staff.getPasswordHash()))
                    .isTrue();
        }
    }

    @Test
    void skipsAccountsThatAlreadyExist() {
        when(staffUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        seed("admin123456", "doctor123456", "doctor123456", "doctor123456").run(null);

        verify(staffUserMapper, never()).insert(any(StaffUser.class));
    }

    @Test
    void skipsAccountsWhenPasswordUnset() {
        when(staffUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        seed("", "", "", "").run(null);

        verify(staffUserMapper, never()).insert(any(StaffUser.class));
    }

    @Test
    void skipsOnlyUnsetPasswordDoctors() {
        when(staffUserMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        // doctors-password 未配置：id 3-15 跳过，admin/doctor.lin/doctor.zhou 仍补种
        seed("admin123456", "doctor123456", "doctor123456", "").run(null);

        var captor = ArgumentCaptor.forClass(StaffUser.class);
        verify(staffUserMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues().stream().map(StaffUser::getUsername))
                .containsExactlyInAnyOrder("admin", "doctor.lin", "doctor.zhou");
    }

    /** 与 StaffUserSeed.EXTRA_DOCTOR_USERNAMES 映射一致（id 3-15），改名时须同步 */
    private static String usernameOf(long doctorId) {
        return switch ((int) doctorId) {
            case 3 -> "doctor.chen";
            case 4 -> "doctor.su";
            case 5 -> "doctor.li";
            case 6 -> "doctor.zhao";
            case 7 -> "doctor.wu";
            case 8 -> "doctor.sun";
            case 9 -> "doctor.zheng";
            case 10 -> "doctor.ma";
            case 11 -> "doctor.he";
            case 12 -> "doctor.huang";
            case 13 -> "doctor.liang";
            case 14 -> "doctor.feng";
            case 15 -> "doctor.han";
            default -> throw new IllegalArgumentException("unexpected doctor id: " + doctorId);
        };
    }
}
