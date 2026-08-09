package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.StandardDepartment;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.mapper.organization.DepartmentMapper;
import com.zhiyu.health.mapper.organization.DoctorMapper;
import com.zhiyu.health.mapper.organization.HospitalCampusMapper;
import com.zhiyu.health.mapper.organization.StandardDepartmentMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleMapper;
import com.zhiyu.health.service.health.PatientMedicalDirectoryService;
import com.zhiyu.health.service.health.mapping.PatientMedicalDirectoryDtoMapper;
import com.zhiyu.health.service.scheduling.SlotWindowGuard;
import com.zhiyu.health.support.TestSlotWindows;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;

/** C 端确定性目录与号源卡逻辑（票 49）：14 天窗口、有号/无号排序、单日过滤、404。 */
class PatientMedicalDirectoryServiceTest {

    private final HospitalCampusMapper hospitalCampusMapper = mock(HospitalCampusMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final StandardDepartmentMapper standardDepartmentMapper = mock(StandardDepartmentMapper.class);
    // 时段截止判断：默认固定到系统当天 10:00（上午未结束），现有用例以 LocalDate.now() 构造今天
    // 上午/下午号源行，均不会被误判截止；已过时段用例用 12:00 的 guard 单独构造 service。
    private final SlotWindowGuard slotWindowGuard = new SlotWindowGuard(
            Clock.fixed(
                    LocalDate.now()
                            .atTime(10, 0)
                            .atZone(ZoneId.of("Asia/Shanghai"))
                            .toInstant(),
                    ZoneId.of("Asia/Shanghai")),
            TestSlotWindows.contractOnly());
    private final PatientMedicalDirectoryService service = new PatientMedicalDirectoryService(
            hospitalCampusMapper,
            departmentMapper,
            standardDepartmentMapper,
            mock(DoctorMapper.class),
            mock(ScheduleMapper.class),
            Mappers.getMapper(PatientMedicalDirectoryDtoMapper.class),
            slotWindowGuard);

    @BeforeEach
    void stubStandardDepartment() {
        StandardDepartment standardDepartment = new StandardDepartment();
        standardDepartment.setId(1L);
        standardDepartment.setCategory("内科");
        standardDepartment.setName("心血管内科");
        when(standardDepartmentMapper.selectById(1L)).thenReturn(standardDepartment);
    }

    @Test
    void daysAreFourteenConsecutiveDatesFromTodayInclusive() {
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, null);

        LocalDate today = LocalDate.now();
        assertThat(view.days()).hasSize(14);
        assertThat(view.days().get(0)).isEqualTo(today.toString());
        assertThat(view.days().get(13)).isEqualTo(today.plusDays(13).toString());
        assertThat(view.doctors()).isEmpty();
        assertThat(view.standardDepartment().name()).isEqualTo("心血管内科");
    }

    @Test
    void slotWindowPassedToMapperIsTodayThroughTodayPlus13() {
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.standardDepartmentSlots(1L, "410100", null, null);

        // 14 天边界：from=today、to=today+13（含），today+14 与过去日期由 SQL 窗口排除
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(departmentMapper)
                .selectDoctorSlotRows(eq(1L), eq("410100"), from.capture(), to.capture(), isNull(), isNull());
        assertThat(from.getValue()).isEqualTo(LocalDate.now());
        assertThat(to.getValue()).isEqualTo(LocalDate.now().plusDays(13));
    }

    @Test
    void bookableDoctorsSortFirstByEarliestSlotThenDistanceThenId() {
        LocalDate today = LocalDate.now();
        // 医生 1：明天上午有号；医生 2：今天下午有号（更早，应排最前）；医生 3：今天下午有号但距离更远
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(
                        slotRow(1L, today.plusDays(1), "上午", 5, 1.0),
                        slotRow(2L, today, "下午", 3, 0.5),
                        slotRow(3L, today, "下午", 3, 9.9)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, null);

        assertThat(view.doctors().stream().map(PatientMedicalDirectoryService.DoctorSlotCard::doctorId))
                .containsExactly(2L, 3L, 1L);
        assertThat(view.doctors()).allMatch(PatientMedicalDirectoryService.DoctorSlotCard::bookable);
    }

    @Test
    void soldOutDoctorIsRetainedWithBookableFalseAndSortsLast() {
        LocalDate today = LocalDate.now();
        // 医生 2 全部约满（remaining=0 的行仍返回），医生 1 有号；无号医生排末尾且不丢失
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(2L, today, "上午", 0, 0.1), slotRow(1L, today, "下午", 4, 5.0)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, null);

        assertThat(view.doctors().stream().map(PatientMedicalDirectoryService.DoctorSlotCard::doctorId))
                .containsExactly(1L, 2L);
        PatientMedicalDirectoryService.DoctorSlotCard soldOut = view.doctors().get(1);
        assertThat(soldOut.bookable()).isFalse();
        assertThat(soldOut.slots()).hasSize(1);
        assertThat(soldOut.slots().get(0).remainingSlots()).isEqualTo(0);
    }

    @Test
    void morningSlotSortsBeforeAfternoonWithinSameDate() {
        LocalDate today = LocalDate.now();
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(1L, today, "下午", 4, 0.1), slotRow(2L, today, "上午", 4, 9.9)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, null);

        assertThat(view.doctors().stream().map(PatientMedicalDirectoryService.DoctorSlotCard::doctorId))
                .containsExactly(2L, 1L);
    }

    @Test
    void dateParamFiltersSlotsToThatDate() {
        LocalDate today = LocalDate.now();
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(1L, today, "上午", 4, 1.0), slotRow(1L, today.plusDays(1), "上午", 4, 1.0)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, today.plusDays(1));

        assertThat(view.days()).hasSize(14);
        assertThat(view.doctors()).hasSize(1);
        assertThat(view.doctors().get(0).slots()).hasSize(1);
        assertThat(view.doctors().get(0).slots().get(0).scheduleDate())
                .isEqualTo(today.plusDays(1).toString());
    }

    @Test
    void doctorWithoutSlotsOnSelectedDateIsRetainedWithBookableFalse() {
        LocalDate today = LocalDate.now();
        // 医生 2 只有今天的排班，医生 1 今天和明天都有；选明天时医生 2 保留但当日无号置灰
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(1L, today.plusDays(1), "上午", 4, 5.0), slotRow(2L, today, "上午", 4, 0.1)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, today.plusDays(1));

        assertThat(view.doctors().stream().map(PatientMedicalDirectoryService.DoctorSlotCard::doctorId))
                .containsExactly(1L, 2L);
        PatientMedicalDirectoryService.DoctorSlotCard noSlotThatDay =
                view.doctors().get(1);
        assertThat(noSlotThatDay.bookable()).isFalse();
        assertThat(noSlotThatDay.slots()).isEmpty();
    }

    @Test
    void doctorWithoutAnyScheduleInWindowIsRetained() {
        LocalDate today = LocalDate.now();
        // 医生 3 窗口内无排班（LEFT JOIN 空行），仍保留在卡片中置灰
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(1L, today, "上午", 4, 1.0), emptySlotRow(3L, 0.2)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, null);

        assertThat(view.doctors().stream().map(PatientMedicalDirectoryService.DoctorSlotCard::doctorId))
                .containsExactly(1L, 3L);
        PatientMedicalDirectoryService.DoctorSlotCard noSchedule =
                view.doctors().get(1);
        assertThat(noSchedule.bookable()).isFalse();
        assertThat(noSchedule.slots()).isEmpty();
    }

    @Test
    void dateParamSortsByDistanceBeforeTimeSlot() {
        LocalDate today = LocalDate.now();
        // 指定日期：最近院区优先于上午/下午——医生 1 上午但更远，医生 2 下午但更近，医生 2 排前
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(1L, today, "上午", 4, 9.9), slotRow(2L, today, "下午", 4, 0.1)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, today);

        assertThat(view.doctors().stream().map(PatientMedicalDirectoryService.DoctorSlotCard::doctorId))
                .containsExactly(2L, 1L);
    }

    @Test
    void dateOutsideWindowThrows400() {
        LocalDate today = LocalDate.now();

        assertThatThrownBy(() -> service.standardDepartmentSlots(1L, "410100", null, today.minusDays(1)))
                .isInstanceOf(ApiException.class)
                .hasMessage("date 须在今天起 14 天内");
        assertThatThrownBy(() -> service.standardDepartmentSlots(1L, "410100", null, today.plusDays(14)))
                .isInstanceOf(ApiException.class)
                .hasMessage("date 须在今天起 14 天内");
    }

    @Test
    void unknownStandardDepartmentThrows404() {
        when(standardDepartmentMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.standardDepartmentSlots(99L, "410100", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("标准科室不存在");
    }

    @Test
    void cityCodeIsPassedThroughAsHardFilter() {
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "999999", null, null);

        // 无本城市数据：医生为空但结构不变（标准科室信息与 14 天日期照返）
        assertThat(view.doctors()).isEmpty();
        assertThat(view.days()).hasSize(14);
        verify(departmentMapper).selectDoctorSlotRows(eq(1L), eq("999999"), any(), any(), isNull(), isNull());
    }

    @Test
    void coordinatesArePassedThroughToSlotQuery() {
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());
        PatientMedicalDirectoryService.Coordinates coordinates =
                new PatientMedicalDirectoryService.Coordinates(34.7572, 113.6458);

        service.standardDepartmentSlots(1L, "410100", coordinates, null);

        verify(departmentMapper).selectDoctorSlotRows(eq(1L), eq("410100"), any(), any(), eq(113.6458), eq(34.7572));
    }

    @Test
    void cardCarriesCampusAddressAndEarliestBookableSlot() {
        // 票 50：号源卡补充院区地址；最早可约由内部排序键改为可序列化结构 {date, time_slot}
        LocalDate today = LocalDate.now();
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(1L, today.plusDays(1), "上午", 5, 1.0), slotRow(1L, today, "下午", 3, 1.0)));

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                service.standardDepartmentSlots(1L, "410100", null, null);

        PatientMedicalDirectoryService.DoctorSlotCard card = view.doctors().get(0);
        assertThat(card.campusAddress()).isEqualTo("郑州市金水区健康路 88 号");
        // 票 60：医生专长从 doctors.specialty 透出到号源卡
        assertThat(card.specialty()).isEqualTo("擅长心血管疾病诊治");
        // 票 62：医生头像 object key 映射为 /api/c/photos 代理 URL 透出到号源卡
        assertThat(card.photoUrl()).isEqualTo("/api/c/photos?key=photos/doc-1.jpg");
        // 最早可约取两天中较早者：今天下午
        assertThat(card.earliestBookable().date()).isEqualTo(today.toString());
        assertThat(card.earliestBookable().timeSlot()).isEqualTo("下午");
    }

    @Test
    void closedTimeWindowSlotIsFilteredFromDepartmentSlotsCard() {
        // 当天上午已过 11:30（Clock 固定 12:00）：上午号源从号源卡移除，下午保留为最早可约
        LocalDate today = LocalDate.now();
        when(departmentMapper.selectDoctorSlotRows(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(slotRow(1L, today, "上午", 5, 1.0), slotRow(1L, today, "下午", 3, 1.0)));
        SlotWindowGuard closedGuard = new SlotWindowGuard(
                Clock.fixed(
                        today.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        PatientMedicalDirectoryService serviceWithClosedGuard = new PatientMedicalDirectoryService(
                hospitalCampusMapper,
                departmentMapper,
                standardDepartmentMapper,
                mock(DoctorMapper.class),
                mock(ScheduleMapper.class),
                Mappers.getMapper(PatientMedicalDirectoryDtoMapper.class),
                closedGuard);

        PatientMedicalDirectoryService.StandardDepartmentSlotsView view =
                serviceWithClosedGuard.standardDepartmentSlots(1L, "410100", null, today);

        PatientMedicalDirectoryService.DoctorSlotCard card = view.doctors().get(0);
        assertThat(card.slots()).hasSize(1);
        assertThat(card.slots().get(0).timeSlot()).isEqualTo("下午");
        assertThat(card.bookable()).isTrue();
        assertThat(card.earliestBookable().timeSlot()).isEqualTo("下午");
    }

    @Test
    void closedTimeWindowSlotIsFilteredFromDoctorSchedules() {
        // 医生排班页出口同样过滤当天已过时段：上午已过 11:30（Clock 固定 12:00）时上午不返回
        LocalDate today = LocalDate.now();
        Schedule morning = schedule(101L, today, TimeSlot.MORNING, 5);
        Schedule afternoon = schedule(102L, today, TimeSlot.AFTERNOON, 3);
        ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
        when(scheduleMapper.selectBookableByDoctor(2L, today)).thenReturn(List.of(morning, afternoon));
        SlotWindowGuard closedGuard = new SlotWindowGuard(
                Clock.fixed(
                        today.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai")),
                TestSlotWindows.contractOnly());
        PatientMedicalDirectoryService serviceWithClosedGuard = new PatientMedicalDirectoryService(
                hospitalCampusMapper,
                departmentMapper,
                standardDepartmentMapper,
                mock(DoctorMapper.class),
                scheduleMapper,
                Mappers.getMapper(PatientMedicalDirectoryDtoMapper.class),
                closedGuard);

        List<PatientMedicalDirectoryService.ScheduleView> views = serviceWithClosedGuard.schedules(2L);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).timeSlot()).isEqualTo("下午");
    }

    @Test
    void schedulesUsesBookableScopeAndKeepsSoldOutRows() {
        // C 端排班列表必须走可挂号口径（SQL 内过滤已停诊与待审核停诊，与挂号拦截同口径），
        // 剩余 0 的行保留供端侧置灰展示"约满"
        ScheduleMapper scheduleMapper = mock(ScheduleMapper.class);
        when(scheduleMapper.selectBookableByDoctor(2L, LocalDate.now()))
                .thenReturn(List.of(schedule(103L, LocalDate.now().plusDays(1), TimeSlot.MORNING, 0)));
        PatientMedicalDirectoryService serviceWithMapper = new PatientMedicalDirectoryService(
                hospitalCampusMapper,
                departmentMapper,
                standardDepartmentMapper,
                mock(DoctorMapper.class),
                scheduleMapper,
                Mappers.getMapper(PatientMedicalDirectoryDtoMapper.class),
                slotWindowGuard);

        List<PatientMedicalDirectoryService.ScheduleView> views = serviceWithMapper.schedules(2L);

        verify(scheduleMapper).selectBookableByDoctor(2L, LocalDate.now());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).remainingSlots()).isZero();
    }

    @Test
    void resolveServiceCityCodePicksNearestCampusCityWithCoordinates() {
        // 票 50：Agent 回调不传 city_code，有坐标时 serviceCities 已按最近院区排序，取首项
        when(hospitalCampusMapper.selectServiceCities(113.6458, 34.7572))
                .thenReturn(List.of(
                        new HospitalCampusMapper.ServiceCityRow("410100", "郑州市", 1.2),
                        new HospitalCampusMapper.ServiceCityRow("410300", "洛阳市", 120.0)));

        String cityCode =
                service.resolveServiceCityCode(new PatientMedicalDirectoryService.Coordinates(34.7572, 113.6458));

        assertThat(cityCode).isEqualTo("410100");
        verify(hospitalCampusMapper).selectServiceCities(113.6458, 34.7572);
    }

    @Test
    void resolveServiceCityCodeFallsBackToFirstServiceCityWithoutCoordinates() {
        // 无坐标时取服务城市聚合列表首项（city_code 稳定序），不写死任何城市
        when(hospitalCampusMapper.selectServiceCities(null, null))
                .thenReturn(List.of(
                        new HospitalCampusMapper.ServiceCityRow("410100", "郑州市", null),
                        new HospitalCampusMapper.ServiceCityRow("410300", "洛阳市", null)));

        assertThat(service.resolveServiceCityCode(null)).isEqualTo("410100");
        verify(hospitalCampusMapper).selectServiceCities(null, null);
    }

    @Test
    void resolveServiceCityCodeFailsWhenNoServiceCityExists() {
        when(hospitalCampusMapper.selectServiceCities(null, null)).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveServiceCityCode(null))
                .isInstanceOf(ApiException.class)
                .hasMessage("暂无可用服务城市");
    }

    private DepartmentMapper.DoctorSlotRow slotRow(
            long doctorId, LocalDate date, String timeSlot, int remaining, Double distanceKm) {
        return new DepartmentMapper.DoctorSlotRow(
                doctorId,
                "医生" + doctorId,
                "主任医师",
                "擅长心血管疾病诊治",
                "photos/doc-" + doctorId + ".jpg",
                new BigDecimal("50.00"),
                1L,
                "郑州智愈综合医院",
                11L,
                "主院区",
                "郑州市金水区健康路 88 号",
                distanceKm,
                100L + doctorId,
                date,
                timeSlot,
                remaining);
    }

    /** 窗口内无排班的医生行（LEFT JOIN 空侧）。 */
    private DepartmentMapper.DoctorSlotRow emptySlotRow(long doctorId, Double distanceKm) {
        return new DepartmentMapper.DoctorSlotRow(
                doctorId,
                "医生" + doctorId,
                "主任医师",
                "擅长心血管疾病诊治",
                null,
                new BigDecimal("50.00"),
                1L,
                "郑州智愈综合医院",
                11L,
                "主院区",
                "郑州市金水区健康路 88 号",
                distanceKm,
                null,
                null,
                null,
                null);
    }

    /** 医生排班页出口测试用：构造一条活跃排班。 */
    private Schedule schedule(long id, LocalDate date, TimeSlot timeSlot, int remaining) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setScheduleDate(date);
        schedule.setTimeSlot(timeSlot);
        schedule.setTotalSlots(remaining);
        schedule.setRemainingSlots(remaining);
        schedule.setIsActive(true);
        return schedule;
    }
}
