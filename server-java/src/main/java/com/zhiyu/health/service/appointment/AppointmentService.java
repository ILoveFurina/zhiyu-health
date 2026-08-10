package com.zhiyu.health.service.appointment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.common.InAppMessage;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.mapper.appointment.AppointmentMapper;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleRequestMapper;
import com.zhiyu.health.service.appointment.mapping.AppointmentDtoMapper;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.health.HealthProfileService;
import com.zhiyu.health.service.scheduling.SlotAccounting;
import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final ScheduleMapper scheduleMapper;
    private final ScheduleRequestMapper scheduleRequestMapper;
    private final InAppMessageMapper messageMapper;
    private final SlotAccounting slotAccounting;
    private final TransactionTemplate transactionTemplate;
    private final HealthProfileService healthProfiles;
    private final PaymentService payments;
    private final Contracts contracts;
    private final AppointmentDtoMapper appointmentDtos;
    private final DisclaimerService disclaimers;
    private final ObjectMapper objectMapper;
    private final SlotWindowGuard slotWindowGuard;

    /**
     * AI 导诊链路挂号：由对话会话触发，允许同一会话重复调用时返回已有挂号单（幂等）。
     * <p>
     * 与 {@link #createDirect} 的区别在于重复策略：AI 链路使用
     * {@code DuplicatePolicy.RETURN_EXISTING}，同一会话内重复挂号返回已有结果；
     * C 端直接挂号使用 {@code DuplicatePolicy.REJECT}，拒绝重复挂号。
     * </p>
     *
     * @param patientId      患者 ID
     * @param conversationId 对话会话 ID（AI 导诊上下文）
     * @param scheduleId     目标排班 ID
     * @return 挂号视图
     */
    public AppointmentView create(long patientId, long conversationId, long scheduleId) {
        CreatedAppointment created = reserve(patientId, conversationId, scheduleId, DuplicatePolicy.RETURN_EXISTING);
        try {
            // 挂号与号源事务已经提交；收费附属记录失败不得撤销真实挂号结果。
            payments.createUnpaid(created.id(), created.registrationFee());
        } catch (RuntimeException ignored) {
            // 后续幂等挂号请求会再次尝试补建收费记录，唯一键避免重复收费。
        }
        return view(created.id());
    }

    /**
     * C 端小程序直接挂号：患者从功能目录选择排班后下单（票 81 修订票 41 边界）。
     * <p>
     * 与 AI 引导挂号统一走"待支付"流程：挂号成功即占用号源，需在规定时间内完成支付，
     * 否则由 {@link #expireOverdueAppointments} 惰性收敛为已取消并释放号源。
     * 重复挂号直接拒绝（{@code DuplicatePolicy.REJECT}），返回 409 冲突。
     * </p>
     *
     * @param patientId  患者 ID
     * @param scheduleId 目标排班 ID
     * @return 挂号视图
     */
    public AppointmentView createDirect(long patientId, long scheduleId) {
        CreatedAppointment created = reserve(patientId, null, scheduleId, DuplicatePolicy.REJECT);
        try {
            // 与 AI 引导挂号同口径建收费记录；附属记录失败不撤销真实挂号结果。
            payments.createUnpaid(created.id(), created.registrationFee());
        } catch (RuntimeException ignored) {
            // 幂等挂号请求会再次尝试补建收费记录，唯一键避免重复收费。
        }
        return view(created.id());
    }

    /**
     * 挂号核心预留逻辑：号源预扣 + 挂号单创建的事务闭环。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>获取患者活跃健康档案</li>
     *   <li>Redis 预扣号源（{@link SlotAccounting#withDeduction}）</li>
     *   <li>事务内：排班行锁 → 有效性校验 → 幂等判重 → PG 扣减 → 生成序号 → 写入挂号单</li>
     *   <li>写入就诊指引站内信（同事务）</li>
     * </ol>
     * <p>
     * 补偿机制：事务失败时 {@code withDeduction} 自动回补 Redis 预扣，保证 Redis/PG 双写一致性。
     *
     * @param patientId       患者 ID
     * @param conversationId  对话会话 ID（AI 导诊时非空，直接挂号时为 null）
     * @param scheduleId      目标排班 ID
     * @param duplicatePolicy 重复挂号策略：RETURN_EXISTING（AI 链路幂等）/ REJECT（C 端拒绝重复）
     * @return 创建结果（挂号单 ID + 挂号费）
     */
    private CreatedAppointment reserve(
            long patientId, Long conversationId, long scheduleId, DuplicatePolicy duplicatePolicy) {
        long profileId = healthProfiles.requireActive(patientId).getId();
        // withDeduction 的补偿范围覆盖整个事务（含提交失败）：已预扣未提交即回补 Redis。
        CreatedAppointment created = slotAccounting.withDeduction(
                scheduleId,
                deduction -> transactionTemplate.execute(status -> {
                    // 排班行锁把幂等判断、序号分配与 PG 对账串成一个临界区，防止并发重复扣减或重号。
                    Schedule schedule = scheduleMapper.selectByIdForUpdate(scheduleId);
                    if (schedule == null || !Boolean.TRUE.equals(schedule.getIsActive())) {
                        throw new ApiException(404, "排班不存在或已停用");
                    }
                    // 时段截止校验：排班当天当前时间已超过出诊时段结束时间则不可再挂号。
                    // 判断经 SlotWindowGuard，与号源查询出口共享同一逻辑与契约 time_slot_windows。
                    if (slotWindowGuard.isClosed(schedule)) {
                        throw new ApiException(409, "该出诊时段已结束，不可再挂号");
                    }
                    // 停诊/调整号源审核冻结：排班存在待审核的停诊或调整号源申请时冻结挂号，
                    // 符合"只有可出诊才可挂号"。审核通过则落盘，驳回则恢复可挂号，期间不允许新增挂号。
                    if (scheduleRequestMapper.countPendingBlockingBySchedule(scheduleId) > 0) {
                        throw new ApiException(409, "该排班正在调整号源或停诊审核中，暂不可挂号");
                    }
                    String cancelledStatus = contracts.appointmentFlow().status("cancelled");
                    Appointment existing = appointmentMapper.selectForProfileAndSchedule(
                            patientId, profileId, scheduleId, cancelledStatus);
                    if (existing != null) {
                        if (duplicatePolicy == DuplicatePolicy.REJECT) {
                            throw new ApiException(409, "请勿重复挂号");
                        }
                        return new CreatedAppointment(existing.getId(), existing.getRegistrationFee());
                    }
                    // 幂等检查通过后才预扣；售罄在此处抛 409 且 Redis 已被 SlotAccounting 回补。
                    deduction.acquire();
                    if (scheduleMapper.decrementRemainingSlots(scheduleId) != 1) {
                        throw new ApiException(409, "号源已约满");
                    }
                    Appointment appointment = new Appointment();
                    appointment.setPatientId(patientId);
                    appointment.setHealthProfileId(profileId);
                    appointment.setConversationId(conversationId);
                    appointment.setScheduleId(scheduleId);
                    appointment.setSequenceNumber(appointmentMapper.nextSequenceNumber(scheduleId));
                    appointment.setRegistrationFee(schedule.getRegistrationFee());
                    // 挂号成功即进入待支付并占用号源（占位等支付，票 81）；
                    // 号源在扣减时已占住，支付完成推进为待就诊，超时/取消才释放。
                    appointment.setStatus(contracts.appointmentFlow().status("pending_payment"));
                    appointment.setPaymentDeadline(OffsetDateTime.now()
                            .plusSeconds(contracts.appointmentFlow().paymentTimeoutSeconds()));
                    appointmentMapper.insert(appointment);
                    writeAppointmentCareMessage(patientId, scheduleId, appointment.getId());
                    return new CreatedAppointment(appointment.getId(), appointment.getRegistrationFee());
                }));
        return created;
    }

    /**
     * 挂号后就诊指引卡（票 43）：事务内联查排班->院区拼装结构化 content，写一条
     * type=appointment_care 的站内消息。地址/楼层/材料/注意事项取自 hospital_campuses 表静态 seed
     * 值（票 49 从医院下沉到院区），非 LLM 生成。与挂号同事务，失败即回滚（含 Redis 号源回补）。
     * 幂等：重复挂号请求在 reserve() 早返回分支不触达此处；DB UNIQUE(related_appointment_id,type) 兜底竞态。
     */
    private void writeAppointmentCareMessage(long patientId, long scheduleId, long appointmentId) {
        ScheduleMapper.CareContext care = scheduleMapper.selectCareContextBySchedule(scheduleId);
        // 排班刚写入即可联查（外键保证 join 命中），缺失属数据完整性异常；
        // 抛出触发事务回滚（含 Redis 号源回补），符合"失败一起回滚无悬空"硬约束，不留悬空挂号。
        if (care == null) {
            throw new IllegalStateException("就诊指引卡上下文缺失，挂号事务回滚：scheduleId=" + scheduleId);
        }
        String scheduleTime =
                (care.scheduleDate() == null ? "" : care.scheduleDate().toString()) + " " + care.timeSlotValue();
        var content = new java.util.LinkedHashMap<String, Object>();
        content.put("greeting", "挂号成功，请按时就诊");
        content.put("hospital_name", care.hospitalName());
        content.put("department_name", care.departmentName());
        content.put("doctor_name", care.doctorName());
        content.put("schedule_time", scheduleTime.trim());
        content.put("address", care.address());
        content.put("floor", care.floor());
        // materials/precautions 在 seed 中以换行分隔，拆成数组供端侧渲染列表
        content.put("materials", splitLines(care.materials()));
        content.put("precautions", splitLines(care.precautions()));
        String contentJson;
        try {
            contentJson = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            // content 全为结构化静态值，序列化失败属装配错误，抛出以触发事务回滚暴露问题。
            throw new IllegalStateException("就诊指引卡 content 序列化失败", exception);
        }
        InAppMessage message = new InAppMessage();
        message.setPatientId(patientId);
        message.setType(contracts.appointmentCare().messageType());
        message.setTitle(contracts.appointmentCare().title());
        message.setContent(contentJson);
        // server-java 出口兜底：免责声明一律经 DisclaimerService 从契约注入，不信任上游。
        message.setDisclaimer(disclaimers.text());
        message.setRelatedAppointmentId(appointmentId);
        messageMapper.insert(message);
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 查询患者的挂号列表。
     * <p>
     * 查询前先执行惰性收敛：把 payment_deadline 已过的待支付单推进为已取消并释放号源（ADR-0033）。
     * 与接诊台入口同构，不引入调度中间件，号源释放靠下次入口访问触发。
     * </p>
     *
     * @param patientId 患者 ID
     * @return 挂号视图列表
     */
    public List<AppointmentView> listForPatient(long patientId) {
        // C 端列表入口惰性收敛：先过期待支付单（释放号源），再过点未叫号已支付单（取消+退款+消息，票 92）。
        expireOverdueAppointments();
        expireUncalledAppointments();
        long profileId = healthProfiles.requireActive(patientId).getId();
        return appointmentMapper.selectViewsByProfile(patientId, profileId).stream()
                .map(this::toView)
                .toList();
    }

    public AppointmentView createWithSummary(long patientId, long conversationId, long scheduleId, String summary) {
        AppointmentView created = create(patientId, conversationId, scheduleId);
        if (created.conditionSummary() != null) {
            // 幂等重试返回已有完整挂号单，保留原会话摘要，禁止被新会话覆盖或误报为发送失败。
            return created;
        }
        try {
            return saveConditionSummary(patientId, conversationId, created.id(), summary);
        } catch (RuntimeException exception) {
            // 创建事务已经独立提交；摘要失败只降级卡片，不回滚或隐藏真实挂号结果。
            return created;
        }
    }

    public AppointmentView saveConditionSummary(
            long patientId, long conversationId, long appointmentId, String summary) {
        long profileId = healthProfiles.requireActive(patientId).getId();
        // 摘要只存纯内容；免责声明在响应装配时由 DisclaimerService 挂载，不入库。
        if (appointmentMapper.updateConditionSummary(
                        appointmentId, patientId, profileId, conversationId, summary.trim())
                != 1) {
            throw new ApiException(404, "挂号单不存在");
        }
        return view(appointmentId);
    }

    /**
     * 患者主动取消挂号。
     * <p>
     * 取消规则（票 90）：
     * <ul>
     *   <li>待支付（PENDING_PAYMENT）：随时可取消，释放号源</li>
     *   <li>已支付（BOOKED）：距就诊开始不足 {@code cancelCutoffMinutes} 分钟不可取消，避免临近就诊释放号源造成调度浪费</li>
     *   <li>其他状态：不可取消</li>
     * </ul>
     * <p>
     * 事务闭环：挂号单状态推进 + 号源 PG 回补 + Redis 退还 + 已支付退款，四者同提交同回滚。
     * 补偿机制：事务失败时 {@code withRefund} 自动撤销 Redis 退还，保证双写一致性。
     *
     * @param patientId     患者 ID
     * @param appointmentId 挂号单 ID
     * @return 取消后的挂号视图
     */
    public AppointmentView cancel(long patientId, long appointmentId) {
        // C 端取消入口惰性收敛：先过期待支付单，再过点未叫号已支付单（取消+退款+消息，票 92）。
        expireOverdueAppointments();
        expireUncalledAppointments();
        long profileId = healthProfiles.requireActive(patientId).getId();
        // withRefund 的补偿范围覆盖整个事务（含提交失败）：已退还未提交即撤销退还。
        Long resultId = slotAccounting.withRefund(refund -> transactionTemplate.execute(status -> {
            // 挂号单行锁保证重复取消只让首次状态转换进入双存储回补分支。
            Appointment appointment = appointmentMapper.selectByIdForUpdate(appointmentId, patientId, profileId);
            if (appointment == null) {
                throw new ApiException(404, "挂号单不存在");
            }
            Contracts.AppointmentFlow flow = contracts.appointmentFlow();
            String cancelledStatus = flow.status("cancelled");
            if (cancelledStatus.equals(appointment.getStatus())) {
                return appointment.getId();
            }
            Contracts.AppointmentFlow.Transition cancel = flow.transitions().get("cancel");
            if (!cancel.allows(appointment.getStatus())) {
                throw new ApiException(409, "当前状态不可取消");
            }
            String bookedStatus = flow.status("booked");
            boolean wasBooked = bookedStatus.equals(appointment.getStatus());
            // 已支付（BOOKED）预约的取消时间窗口（票 90）：距号源起始时间不足 cancel_cutoff_minutes 分钟不可取消，
            // 避免临近就诊时段释放号源造成的调度浪费。待支付（PENDING_PAYMENT）不受此约束（鼓励尽早释放号源）。
            if (wasBooked) {
                Schedule schedule = scheduleMapper.selectById(appointment.getScheduleId());
                if (schedule != null
                        && slotWindowGuard.isPastCancelCutoff(
                                schedule.getScheduleDate(),
                                schedule.getTimeSlot() == null
                                        ? null
                                        : schedule.getTimeSlot().getValue(),
                                flow.cancelCutoffMinutes())) {
                    throw new ApiException(409, "距就诊开始不足半小时，不可取消");
                }
            }
            // cancel.from = [PENDING_PAYMENT, BOOKED]，两条来源态都释放号源。
            if (appointmentMapper.markCancelled(
                                    appointmentId,
                                    cancel.from().get(0),
                                    cancel.from().get(1),
                                    cancel.to())
                            != 1
                    || scheduleMapper.incrementRemainingSlots(appointment.getScheduleId()) != 1) {
                throw new IllegalStateException("取消挂号的 PostgreSQL 回补失败");
            }
            // 已支付预约取消需同步退款（票 90）：PAID -> REFUNDED，与号源回补同事务同提交同回滚。
            // refundIfPaid 的 CAS 守卫幂等：重复取消只首次退款，非 PAID 安全跳过。
            if (wasBooked) {
                payments.refundIfPaid(appointmentId);
            }
            refund.grant(appointment.getScheduleId());
            return appointment.getId();
        }));
        return view(resultId);
    }

    /**
     * 支付超时惰性收敛（票 81，ADR-0033）。
     * <p>
     * 在接诊台 / C 端列表 / pay / cancel 入口同步调用，把 {@code payment_deadline} 已过的
     * 待支付挂号单推进为已取消并释放号源。与在线问诊 {@code expireOverdue} 同构——
     * 不引入调度中间件，号源释放靠下次入口访问触发。
     * </p>
     * <p>
     * 每条独立 withRefund 事务：{@code markCancelled} 的 CAS 守卫并发重复收敛（返回 0 即跳过），
     * 单条失败的事务整体回滚不影响其余条目。
     * </p>
     */
    public void expireOverdueAppointments() {
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        List<AppointmentMapper.OverdueAppointment> overdue =
                appointmentMapper.selectOverduePending(flow.status("pending_payment"));
        for (AppointmentMapper.OverdueAppointment item : overdue) {
            try {
                cancelOverdue(item.id(), item.scheduleId());
            } catch (RuntimeException ignored) {
                // 单条收敛失败不阻断其余条目；下次入口访问会再次尝试该条。
            }
        }
    }

    /**
     * 超时取消单条挂号：系统级收敛，无患者身份校验。
     * <p>
     * CAS 守卫：并发收敛或患者已手动取消时状态不再是待支付，UPDATE 返回 0 即安全跳过，
     * 不会误取消已支付或已取消的挂号单。
     * </p>
     *
     * @param appointmentId 挂号单 ID
     * @param scheduleId    关联排班 ID（用于号源回补）
     */
    private void cancelOverdue(long appointmentId, long scheduleId) {
        slotAccounting.withRefund(refund -> transactionTemplate.execute(status -> {
            Contracts.AppointmentFlow flow = contracts.appointmentFlow();
            // CAS 守卫：并发收敛或患者已手动取消时状态不再是待支付，UPDATE 返回 0 即安全跳过。
            if (appointmentMapper.markCancelled(
                            appointmentId,
                            flow.status("pending_payment"),
                            flow.status("booked"),
                            flow.status("cancelled"))
                    != 1) {
                return null;
            }
            if (scheduleMapper.incrementRemainingSlots(scheduleId) != 1) {
                throw new IllegalStateException("超时取消的 PostgreSQL 号源回补失败");
            }
            refund.grant(scheduleId);
            return null;
        }));
    }

    /**
     * 过点未叫号已支付预约惰性收敛（票 92，反转 ADR-0034 第 3 条）。
     * <p>
     * 在接诊台 / C 端列表 / cancel 入口与 {@link #expireOverdueAppointments} 并排调用，把当天 BOOKED 但
     * 已过号源时段窗口 end 的预约推进为已取消 + 退款 + 站内消息。与待支付超时收敛同构--不引入调度
     * 中间件，号源释放与退款靠下次入口访问触发。过点判定复用 {@link SlotWindowGuard#isClosed}（当天且
     * now > end，fail-open 未知窗口不取消，与 {@code isPastCancelCutoff} 同口径）。
     * </p>
     * <p>
     * 每条独立 withRefund 事务：{@code markCancelledIfBooked} 的 CAS 守卫并发重复收敛（返回 0 即跳过），
     * 单条失败的事务整体回滚不影响其余条目。
     * </p>
     */
    public void expireUncalledAppointments() {
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        List<AppointmentMapper.UncalledAppointment> uncalled =
                appointmentMapper.selectUncalledBookedToday(flow.status("booked"));
        for (AppointmentMapper.UncalledAppointment item : uncalled) {
            // isClosed 内部已判当天（查询已限今天，冗余安全）+ window 非空 + now > end；未知窗口 fail-open 不取消。
            if (!slotWindowGuard.isClosed(item.scheduleDate(), item.timeSlot())) {
                continue;
            }
            try {
                cancelUncalled(item.id(), item.scheduleId(), item.patientId());
            } catch (RuntimeException ignored) {
                // 单条收敛失败不阻断其余条目；下次入口访问会再次尝试该条。
            }
        }
    }

    /**
     * 过点未叫号单条已支付预约的系统取消：无患者身份校验，同事务退款 + 发消息。
     * <p>
     * CAS 守卫：并发收敛或患者已手动取消/已叫号（IN_PROGRESS）时状态不再是 BOOKED，UPDATE 返回 0 即
     * 安全跳过，不会误取消已叫号或已取消的挂号单。退款 CAS（PAID->REFUNDED）与消息 insertIgnoreConflict
     * 均幂等，重复收敛只首次生效。
     * </p>
     *
     * @param appointmentId 挂号单 ID
     * @param scheduleId    关联排班 ID（用于号源回补）
     * @param patientId     患者 ID（用于写站内消息）
     */
    private void cancelUncalled(long appointmentId, long scheduleId, long patientId) {
        slotAccounting.withRefund(refund -> transactionTemplate.execute(status -> {
            Contracts.AppointmentFlow flow = contracts.appointmentFlow();
            Contracts.AppointmentFlow.Transition autoCancel = flow.transitions().get("auto_cancel_uncalled");
            // CAS 守卫：状态不再是 BOOKED 则返回 0 跳过（并发收敛/患者已取消/已叫号）。
            if (appointmentMapper.markCancelledIfBooked(
                            appointmentId, autoCancel.from().get(0), autoCancel.to())
                    != 1) {
                return null;
            }
            if (scheduleMapper.incrementRemainingSlots(scheduleId) != 1) {
                throw new IllegalStateException("过点未叫号取消的 PostgreSQL 号源回补失败");
            }
            // 已支付预约系统取消需同步退款（票 90/91）：PAID -> REFUNDED，与号源回补同事务同提交同回滚。
            // refundIfPaid 的 CAS 守卫幂等：重复收敛只首次退款，非 PAID 安全跳过。
            payments.refundIfPaid(appointmentId);
            // 站内消息：医生暂未接诊，费用已原路返回。insertIgnoreConflict 幂等（UNIQUE(related_appointment_id, type)）。
            writeAutoCancelledMessage(patientId, appointmentId);
            refund.grant(scheduleId);
            return null;
        }));
    }

    /**
     * 过点未叫号自动取消的站内消息（票 92）：纯文本，content 为契约固定文案「医生暂未接诊，费用已原路返回」。
     * 幂等写入（ON CONFLICT DO NOTHING）：重复收敛撞 UNIQUE(related_appointment_id, type) 返回 0 且事务不受损
     * （PG 约束违例会 abort 事务，Java 侧 catch 无法挽救，故用 ON CONFLICT 而非 try/catch，与票 60 同构）。
     */
    private void writeAutoCancelledMessage(long patientId, long appointmentId) {
        Contracts.AppointmentFlow.UncalledNotice notice =
                contracts.appointmentFlow().uncalledNotice();
        InAppMessage message = new InAppMessage();
        message.setPatientId(patientId);
        message.setType(notice.messageType());
        message.setTitle(notice.title());
        message.setContent(notice.content());
        // server-java 出口兜底：免责声明一律经 DisclaimerService 从契约注入，不信任上游。
        message.setDisclaimer(disclaimers.text());
        message.setRelatedAppointmentId(appointmentId);
        messageMapper.insertIgnoreConflict(message);
    }

    private AppointmentView view(Long appointmentId) {
        if (appointmentId == null) {
            throw new IllegalStateException("挂号事务未返回挂号单");
        }
        Appointment appointment = appointmentMapper.selectViewById(appointmentId);
        if (appointment == null) {
            throw new IllegalStateException("挂号单写入后不可见");
        }
        return toView(appointment);
    }

    private AppointmentView toView(Appointment appointment) {
        String paymentStatus = appointment.getPaymentStatus();
        String paymentStatusLabel = paymentStatus == null
                ? null
                : contracts.paymentFlow().statusLabels().get(paymentStatus);
        Contracts.AppointmentFlow flow = contracts.appointmentFlow();
        boolean cancellable = isCancellable(appointment, flow);
        return appointmentDtos.toView(
                appointment,
                appointment.getStatus(),
                flow.statusLabel(appointment.getStatus()),
                paymentStatusLabel,
                cancellable);
    }

    /**
     * 计算挂号单是否可取消（票 90）。
     * <p>
     * 后端统一计算后下发 {@code cancellable} 标志，前端不自行复制时段表逻辑：
     * <ul>
     *   <li>待支付（PENDING_PAYMENT）：支付截止未过才可取消（过期后由惰性收敛推进为已取消）</li>
     *   <li>已支付（BOOKED）：未过取消截止时刻（号源起始时间前 {@code cancelCutoffMinutes}）才可取消</li>
     *   <li>其他状态（就诊中/已取消/已接诊）：不可取消</li>
     * </ul>
     *
     * @param appointment 挂号单实体
     * @param flow        挂号状态机契约
     * @return true 表示患者当前可主动取消该挂号
     */
    private boolean isCancellable(Appointment appointment, Contracts.AppointmentFlow flow) {
        String pendingPayment = flow.status("pending_payment");
        if (pendingPayment.equals(appointment.getStatus())) {
            // 待支付：支付截止未过才可取消（过期后由惰性收敛推进为已取消）。
            OffsetDateTime deadline = appointment.getPaymentDeadline();
            return deadline == null || deadline.isAfter(OffsetDateTime.now());
        }
        String booked = flow.status("booked");
        if (booked.equals(appointment.getStatus())) {
            // 已支付：未过取消截止时刻才可取消；scheduleDate/timeSlot 为 null（视图未联查到）时安全返回 false。
            return !slotWindowGuard.isPastCancelCutoff(
                    appointment.getScheduleDate(),
                    appointment.getTimeSlot() == null
                            ? null
                            : appointment.getTimeSlot().getValue(),
                    flow.cancelCutoffMinutes());
        }
        return false;
    }

    /**
     * 判断挂号单是否允许发起支付。
     * <p>
     * 条件：收费单状态为 UNPAID 且挂号单状态为 PENDING_PAYMENT。
     * 由 controller 调用后下发 {@code payment_payable} 标志给前端。
     * </p>
     *
     * @param paymentStatus     收费单状态
     * @param appointmentStatus 挂号单状态
     * @return true 表示允许发起支付
     */
    public boolean isPaymentPayable(String paymentStatus, String appointmentStatus) {
        return contracts.paymentFlow().statuses().get("unpaid").equals(paymentStatus)
                && contracts.appointmentFlow().status("pending_payment").equals(appointmentStatus);
    }

    /**
     * 挂号单视图 DTO：service 层内部传递用，最终经 {@link AppointmentDtoMapper} 转换为 controller 输出。
     *
     * @param id                挂号单 ID
     * @param scheduleId        排班 ID
     * @param doctorId          医生 ID
     * @param doctorName        医生姓名
     * @param departmentName    科室名称
     * @param scheduleDate      就诊日期
     * @param timeSlot          就诊时段
     * @param sequenceNumber    就诊序号
     * @param statusCode        状态编码（契约值）
     * @param status            状态显示文本
     * @param registrationFee   挂号费
     * @param paymentStatus     收费单状态编码
     * @param paymentStatusLabel 收费单状态显示文本
     * @param conditionSummary  病情摘要
     * @param hospitalName      医院名称
     * @param campusName        院区名称
     * @param campusAddress     院区地址
     * @param createdAt         创建时间
     * @param paymentDeadline   支付截止时间
     * @param cancellable       是否可取消
     */
    public record AppointmentView(
            Long id,
            Long scheduleId,
            Long doctorId,
            String doctorName,
            String departmentName,
            String scheduleDate,
            String timeSlot,
            Integer sequenceNumber,
            String statusCode,
            String status,
            BigDecimal registrationFee,
            String paymentStatus,
            String paymentStatusLabel,
            String conditionSummary,
            String hospitalName,
            String campusName,
            String campusAddress,
            String createdAt,
            String paymentDeadline,
            boolean cancellable) {}

    /** 挂号创建中间结果：仅承载挂号单 ID 与挂号费，供后续建支付单使用。 */
    private record CreatedAppointment(Long id, BigDecimal registrationFee) {}

    /** 重复挂号策略：AI 导诊链路幂等返回已有挂号，C 端直接挂号拒绝重复。 */
    private enum DuplicatePolicy {
        RETURN_EXISTING,
        REJECT
    }
}
