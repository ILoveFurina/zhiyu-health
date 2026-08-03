package com.zhiyu.health.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.MedCheckinRecord;
import com.zhiyu.health.entity.Prescription;
import com.zhiyu.health.entity.PrescriptionItem;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.MedCheckinRecordMapper;
import com.zhiyu.health.mapper.PrescriptionItemMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.service.mapping.MedCheckinDtoMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 服药打卡业务服务（ADR-0017）：
 * - eager 生成：处方审核通过时按 duration 解析的天数展开每日一条 PENDING，ON CONFLICT DO NOTHING 幂等；
 * - 打卡幂等：条件 UPDATE 只推进 PENDING，CHECKED 不可回退；
 * - streak 现算：从今天/昨天往前数连续 CHECKED 的 due_date，漏一天归零，不存派生列。
 * server-py 不参与，全部 server-java 直写直读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedCheckinService extends ServiceImpl<MedCheckinRecordMapper, MedCheckinRecord> {
    // 写死 Asia/Shanghai：本地三服务 + 云数据拓扑下不依赖 JVM/PG 时区漂移（ADR-0017 Q5）。
    private static final ZoneId STREAK_ZONE = ZoneId.of("Asia/Shanghai");
    // duration 最小解析：数字 + 天/周/月单位；抓不到默认 7 天并记日志（ADR-0017 Q2）。
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)\\s*(天|日|周|月)");
    private static final int DEFAULT_DURATION_DAYS = 7;

    private final MedCheckinRecordMapper checkinMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final AppointmentMapper appointmentMapper;
    private final DisclaimerService disclaimers;
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final MedCheckinDtoMapper dtoMapper;

    /**
     * 处方审核通过时 eager 预生成打卡提醒：按每条明细的 duration 展开每日一条 PENDING。
     * 生成幂等由 UNIQUE(prescription_item_id, due_date) + ON CONFLICT DO NOTHING 兜底，
     * 重复审核/重投静默吞掉，不抛异常。
     */
    public void generateForApprovedPrescription(long prescriptionId) {
        Prescription prescription = prescriptionMapper.selectDetailedById(prescriptionId);
        if (prescription == null) {
            return;
        }
        // 处方行不带 patient/health_profile，必须经 appointment 反查（ADR-0017 Q6 直接式 FK 的来源）。
        Appointment appointment = appointmentMapper.selectById(prescription.getAppointmentId());
        if (appointment == null) {
            return;
        }
        long patientId = appointment.getPatientId();
        long profileId = appointment.getHealthProfileId();
        String pendingStatus = status("pending");
        String disclaimer = disclaimers.text();
        LocalDate start = LocalDate.now(STREAK_ZONE);
        for (PrescriptionItem item : itemMapper.selectDetailed(prescriptionId)) {
            int days = parseDurationDays(item.getDuration());
            for (int offset = 0; offset < days; offset++) {
                MedCheckinRecord record = new MedCheckinRecord();
                record.setPatientId(patientId);
                record.setHealthProfileId(profileId);
                record.setPrescriptionId(prescriptionId);
                record.setPrescriptionItemId(item.getId());
                record.setMedicationName(item.getMedicationName());
                record.setDosage(item.getDosage());
                record.setFrequency(item.getFrequency());
                record.setDueDate(start.plusDays(offset));
                record.setStatus(pendingStatus);
                record.setDisclaimer(disclaimer);
                checkinMapper.insertIgnore(record);
            }
        }
    }

    /** C 端消息页聚合：当前档案下到点未打卡的提醒，按提醒日升序。 */
    public List<MedCheckinView> pendingReminders(long patientId) {
        long profileId = healthProfiles.requireActive(patientId).getId();
        LocalDate today = LocalDate.now(STREAK_ZONE);
        return checkinMapper.selectPendingDue(patientId, profileId, today, status("pending")).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 打卡：条件 UPDATE 只推进 PENDING，affectedRows=1 首次、=0 重复或不存在。
     * 越权档案（记录不属于当前 patient）返回 404 而非 403，避免泄露存在性。
     * streak 在打卡成功后现算返回。
     */
    public MedCheckinView check(long patientId, long recordId) {
        MedCheckinRecord record = checkinMapper.selectOwned(recordId, patientId);
        if (record == null) {
            throw new ApiException(404, "打卡记录不存在");
        }
        int affected = checkinMapper.check(recordId, status("checked"), status("pending"));
        if (affected == 0) {
            // 重复点击：返回当前已打卡状态 + 现算 streak，幂等不报错。
            MedCheckinRecord current = checkinMapper.selectOwned(recordId, patientId);
            return toView(current, streak(patientId, current.getHealthProfileId()));
        }
        MedCheckinRecord checked = checkinMapper.selectOwned(recordId, patientId);
        return toView(checked, streak(patientId, checked.getHealthProfileId()));
    }

    /**
     * streak 现算：今天已打从今天数、今天未到点从昨天数、遇到第一个缺口停（漏一天归零）。
     * 不存派生列，避免"存了 7 但昨天漏了"的一致性漂移（ADR-0017 Q5）。
     */
    public int streak(long patientId, long profileId) {
        List<LocalDate> dates = checkinMapper.selectCheckedDatesDescending(patientId, profileId, status("checked"));
        if (dates.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now(STREAK_ZONE);
        LocalDate cursor = dates.get(0).equals(today) ? today : today.minusDays(1);
        // 第一个已打日期不是今天也不是昨天，说明已断档，streak=0。
        if (!dates.get(0).equals(cursor)) {
            return 0;
        }
        int streak = 0;
        for (LocalDate due : dates) {
            if (due.equals(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            } else if (due.isBefore(cursor)) {
                break;
            }
        }
        return streak;
    }

    private int parseDurationDays(String duration) {
        if (duration == null || duration.isBlank()) {
            log.warn("处方明细 duration 为空，打卡提醒默认 {} 天", DEFAULT_DURATION_DAYS);
            return DEFAULT_DURATION_DAYS;
        }
        Matcher matcher = DURATION_PATTERN.matcher(duration);
        if (!matcher.find()) {
            log.warn("处方明细 duration 无法解析 [{}]，打卡提醒默认 {} 天", duration, DEFAULT_DURATION_DAYS);
            return DEFAULT_DURATION_DAYS;
        }
        int amount = Integer.parseInt(matcher.group(1));
        String unit = matcher.group(2);
        return switch (unit) {
            case "天", "日" -> amount;
            case "周" -> amount * 7;
            case "月" -> amount * 30;
            default -> DEFAULT_DURATION_DAYS;
        };
    }

    private MedCheckinView toView(MedCheckinRecord record) {
        return toView(record, null);
    }

    private MedCheckinView toView(MedCheckinRecord record, Integer streak) {
        String statusLabel =
                contracts.medCheckinFlow().statusLabels().getOrDefault(record.getStatus(), record.getStatus());
        String checkedAtText =
                record.getCheckedAt() == null ? null : record.getCheckedAt().toString();
        return dtoMapper.toView(record, statusLabel, checkedAtText, streak);
    }

    private String status(String name) {
        return contracts.medCheckinFlow().statuses().get(name);
    }
}
