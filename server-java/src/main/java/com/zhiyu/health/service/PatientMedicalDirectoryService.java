package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Doctor;
import com.zhiyu.health.entity.StandardDepartment;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.DoctorMapper;
import com.zhiyu.health.mapper.HospitalCampusMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import com.zhiyu.health.mapper.StandardDepartmentMapper;
import com.zhiyu.health.service.mapping.PatientMedicalDirectoryDtoMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * C 端确定性医疗目录与号源（票 49，ADR-0027）：服务城市由院区动态聚合，城市是医院与号源查询的
 * 硬筛选边界；标准科室号源只经 standard_department_id 映射匹配，所有数量与最早可约时间在服务端计算。
 */
@Service
@RequiredArgsConstructor
public class PatientMedicalDirectoryService {

    /** 号源窗口：今天起连续 14 个自然日（含今天），票 49 硬约定 */
    static final int SLOT_DAYS = 14;

    private final HospitalCampusMapper hospitalCampusMapper;
    private final DepartmentMapper departmentMapper;
    private final StandardDepartmentMapper standardDepartmentMapper;
    private final DoctorMapper doctorMapper;
    private final ScheduleMapper scheduleMapper;
    private final PatientMedicalDirectoryDtoMapper directoryDtos;
    private final SlotWindowGuard slotWindowGuard;

    public List<CityView> serviceCities(Coordinates coordinates) {
        return hospitalCampusMapper
                .selectServiceCities(
                        coordinates == null ? null : coordinates.longitude(),
                        coordinates == null ? null : coordinates.latitude())
                .stream()
                .map(row -> new CityView(row.cityCode(), row.cityName()))
                .toList();
    }

    public List<HospitalView> hospitals(String cityCode, Coordinates coordinates) {
        return hospitalCampusMapper
                .selectHospitalsByCity(
                        cityCode,
                        coordinates == null ? null : coordinates.longitude(),
                        coordinates == null ? null : coordinates.latitude())
                .stream()
                .map(row -> new HospitalView(
                        row.hospitalId(), row.name(), row.level(), row.campusId(), row.campusName(), row.distanceKm()))
                .toList();
    }

    public List<CampusView> campuses(long hospitalId, Coordinates coordinates) {
        return hospitalCampusMapper
                .selectCampusesByHospital(
                        hospitalId,
                        coordinates == null ? null : coordinates.longitude(),
                        coordinates == null ? null : coordinates.latitude())
                .stream()
                .map(row -> new CampusView(row.campusId(), row.name(), row.address(), row.distanceKm()))
                .toList();
    }

    public List<CampusDepartmentView> campusDepartments(long campusId) {
        return departmentMapper.selectByCampusOrdered(campusId).stream()
                .map(row -> new CampusDepartmentView(
                        row.departmentId(), row.name(), row.categoryName(), row.floor(), row.location()))
                .toList();
    }

    public List<StandardCategoryView> standardDepartments(String cityCode) {
        Map<String, List<StandardDepartmentItem>> byCategory = new LinkedHashMap<>();
        departmentMapper.selectStandardCatalogByCity(cityCode).forEach(row -> byCategory
                .computeIfAbsent(row.category(), key -> new ArrayList<>())
                .add(new StandardDepartmentItem(row.id(), row.name())));
        return byCategory.entrySet().stream()
                .map(entry -> new StandardCategoryView(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 服务端城市解析（票 50）：Agent 回调不传 city_code，由服务端解析当前服务城市。
     * 复用 serviceCities 聚合序——有坐标时按最近院区所在城市排序，无坐标时回退 city_code
     * 稳定序，两种情况都取首项；城市只来自院区数据，不写死任何城市。
     */
    public String resolveServiceCityCode(Coordinates coordinates) {
        return serviceCities(coordinates).stream()
                .findFirst()
                .map(CityView::cityCode)
                .orElseThrow(() -> new ApiException(503, "暂无可用服务城市"));
    }

    /**
     * 标准科室号源卡：无论是否有号都返回同一种结构。days 固定为今天起 14 个自然日；
     * 号源窗口边界（含 today+13、排除过去）由 SQL 保证。医生以窗口内全部排班聚合，
     * date 参数只把每个医生的 slots 过滤到单日，不过滤掉医生（当日无号即 bookable=false 置灰）。
     * 排序：无 date 时有号医生优先按最早可约（日期 + 上午先于下午），再按最近院区距离（NULL 兜底）；
     * 指定 date 时有号优先，按最近院区距离后上午/下午；均用 doctor_id 稳定兜底避免刷新跳动；
     * 无号医生排在末尾（同序规则）。
     */
    public StandardDepartmentSlotsView standardDepartmentSlots(
            long standardDepartmentId, String cityCode, Coordinates coordinates, LocalDate date) {
        StandardDepartment standardDepartment = standardDepartmentMapper.selectById(standardDepartmentId);
        if (standardDepartment == null) {
            throw new ApiException(404, "标准科室不存在");
        }
        LocalDate today = LocalDate.now();
        if (date != null && (date.isBefore(today) || date.isAfter(today.plusDays(SLOT_DAYS - 1)))) {
            throw new ApiException(400, "date 须在今天起 14 天内");
        }
        List<DepartmentMapper.DoctorSlotRow> rows = departmentMapper.selectDoctorSlotRows(
                standardDepartmentId,
                cityCode,
                today,
                today.plusDays(SLOT_DAYS - 1),
                coordinates == null ? null : coordinates.longitude(),
                coordinates == null ? null : coordinates.latitude());

        Map<Long, DoctorSlotCardBuilder> byDoctor = new LinkedHashMap<>();
        rows.forEach(row -> byDoctor.computeIfAbsent(row.doctorId(), key -> new DoctorSlotCardBuilder(row))
                .add(row));
        List<DoctorSlotCard> doctors = byDoctor.values().stream()
                .map(builder -> builder.build(date, slotWindowGuard))
                .sorted(date == null ? DOCTOR_CARD_ORDER : DOCTOR_CARD_ORDER_ON_DATE)
                .toList();

        List<String> days = IntStream.range(0, SLOT_DAYS)
                .mapToObj(offset -> today.plusDays(offset).toString())
                .toList();
        return new StandardDepartmentSlotsView(
                new StandardDepartmentInfo(
                        standardDepartment.getId(), standardDepartment.getName(), standardDepartment.getCategory()),
                days,
                doctors);
    }

    /** 医生卡稳定排序（无 date）：bookable 优先 → 最早可约（NULL 最后）→ 距离（NULL 最后）→ doctor_id。 */
    private static final Comparator<DoctorSlotCard> DOCTOR_CARD_ORDER = Comparator.comparing(
                    (DoctorSlotCard card) -> card.bookable() ? 0 : 1)
            .thenComparing(DoctorSlotCard::earliestBookable, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DoctorSlotCard::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(DoctorSlotCard::doctorId);

    /** 医生卡稳定排序（指定 date）：bookable 优先 → 最近院区距离（NULL 最后）→ 上午先于下午 → doctor_id。 */
    private static final Comparator<DoctorSlotCard> DOCTOR_CARD_ORDER_ON_DATE = Comparator.comparing(
                    (DoctorSlotCard card) -> card.bookable() ? 0 : 1)
            .thenComparing(DoctorSlotCard::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DoctorSlotCard::earliestBookable, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(DoctorSlotCard::doctorId);

    public List<DoctorView> doctors(long departmentId) {
        return doctorMapper
                .selectList(Wrappers.<Doctor>lambdaQuery()
                        .eq(Doctor::getDepartmentId, departmentId)
                        .orderByAsc(Doctor::getName, Doctor::getId))
                .stream()
                .map(directoryDtos::toDoctorView)
                .toList();
    }

    public List<ScheduleView> schedules(long doctorId) {
        return scheduleMapper.selectBookableByDoctor(doctorId, LocalDate.now()).stream()
                .filter(schedule -> !slotWindowGuard.isClosed(schedule))
                .map(directoryDtos::toScheduleView)
                .toList();
    }

    /** 上午/下午排序权重：与 SQL 的 CASE 序一致，未知时段兜底排尾。 */
    private static int timeSlotOrder(String timeSlot) {
        return switch (timeSlot) {
            case "上午" -> 1;
            case "下午" -> 2;
            default -> 3;
        };
    }

    /** 最早可约：日期 + 时段；排序上午先于下午，序列化为 {date, time_slot} 供端侧与 Agent 摘要展示（票 50）。 */
    public record EarliestSlot(String date, @JsonProperty("time_slot") String timeSlot)
            implements Comparable<EarliestSlot> {
        @Override
        public int compareTo(EarliestSlot other) {
            int byDate = date.compareTo(other.date);
            return byDate != 0 ? byDate : Integer.compare(timeSlotOrder(timeSlot), timeSlotOrder(other.timeSlot));
        }
    }

    /** 同一医生的排班行聚合器：行已按（日期、时段）有序；无排班行（schedule_id 为 NULL）只贡献医生身份。 */
    private static final class DoctorSlotCardBuilder {
        private final DepartmentMapper.DoctorSlotRow first;
        private final List<SlotItem> slots = new ArrayList<>();

        DoctorSlotCardBuilder(DepartmentMapper.DoctorSlotRow first) {
            this.first = first;
        }

        void add(DepartmentMapper.DoctorSlotRow row) {
            if (row.scheduleId() != null) {
                slots.add(new SlotItem(
                        row.scheduleId(), row.scheduleDate().toString(), row.timeSlot(), row.remainingSlots()));
            }
        }

        /** dateFilter 非空时 slots 过滤到单日；当天已过时段从号源卡中移除，bookable 与最早可约按过滤后 slots 重算。 */
        DoctorSlotCard build(LocalDate dateFilter, SlotWindowGuard slotWindowGuard) {
            List<SlotItem> filtered = slots;
            if (dateFilter != null) {
                LocalDate target = dateFilter;
                filtered = slots.stream()
                        .filter(slot -> slot.scheduleDate().equals(target.toString()))
                        .toList();
            }
            filtered = filtered.stream()
                    .filter(slot -> !slotWindowGuard.isClosed(LocalDate.parse(slot.scheduleDate()), slot.timeSlot()))
                    .toList();
            boolean bookable = false;
            EarliestSlot earliestBookable = null;
            for (SlotItem slot : filtered) {
                if (slot.remainingSlots() > 0) {
                    bookable = true;
                    EarliestSlot candidate = new EarliestSlot(slot.scheduleDate(), slot.timeSlot());
                    if (earliestBookable == null || candidate.compareTo(earliestBookable) < 0) {
                        earliestBookable = candidate;
                    }
                }
            }
            return new DoctorSlotCard(
                    first.doctorId(),
                    first.doctorName(),
                    first.title(),
                    first.specialty(),
                    // 票 62：号源卡医生条透出头像（/api/c/photos 代理 URL，空 key 为空串走姓氏圆降级）
                    PhotoUrls.cUrl(first.photoUrl()),
                    first.registrationFee(),
                    first.hospitalId(),
                    first.hospitalName(),
                    first.campusId(),
                    first.campusName(),
                    first.campusAddress(),
                    first.distanceKm(),
                    bookable,
                    earliestBookable,
                    filtered);
        }
    }

    public record CityView(@JsonProperty("city_code") String cityCode, @JsonProperty("city_name") String cityName) {}

    public record HospitalView(
            @JsonProperty("hospital_id") long hospitalId,
            String name,
            String level,
            @JsonProperty("campus_id") long campusId,
            @JsonProperty("campus_name") String campusName,
            @JsonProperty("distance_km") Double distanceKm) {}

    public record CampusView(
            @JsonProperty("campus_id") long campusId,
            String name,
            String address,
            @JsonProperty("distance_km") Double distanceKm) {}

    public record CampusDepartmentView(
            @JsonProperty("department_id") long departmentId,
            String name,
            @JsonProperty("category_name") String categoryName,
            String floor,
            String location) {}

    public record StandardDepartmentItem(long id, String name) {}

    public record StandardCategoryView(String category, List<StandardDepartmentItem> departments) {}

    public record StandardDepartmentInfo(long id, String name, String category) {}

    public record StandardDepartmentSlotsView(
            @JsonProperty("standard_department") StandardDepartmentInfo standardDepartment,
            List<String> days,
            List<DoctorSlotCard> doctors) {}

    public record DoctorSlotCard(
            @JsonProperty("doctor_id") long doctorId,
            @JsonProperty("doctor_name") String doctorName,
            String title,
            String specialty,
            @JsonProperty("photo_url") String photoUrl,
            @JsonProperty("registration_fee") BigDecimal registrationFee,
            @JsonProperty("hospital_id") long hospitalId,
            @JsonProperty("hospital_name") String hospitalName,
            @JsonProperty("campus_id") long campusId,
            @JsonProperty("campus_name") String campusName,
            @JsonProperty("campus_address") String campusAddress,
            @JsonProperty("distance_km") Double distanceKm,
            boolean bookable,
            @JsonProperty("earliest_bookable") EarliestSlot earliestBookable,
            List<SlotItem> slots) {}

    public record SlotItem(
            @JsonProperty("schedule_id") long scheduleId,
            @JsonProperty("schedule_date") String scheduleDate,
            @JsonProperty("time_slot") String timeSlot,
            @JsonProperty("remaining_slots") int remainingSlots) {}

    public record DoctorView(
            @JsonProperty("doctor_id") long doctorId,
            @JsonProperty("department_id") long departmentId,
            String name,
            String title,
            @JsonProperty("registration_fee") BigDecimal registrationFee,
            String specialty,
            @JsonProperty("photo_url") String photoUrl) {}

    public record ScheduleView(
            @JsonProperty("schedule_id") long scheduleId,
            @JsonProperty("doctor_id") long doctorId,
            @JsonProperty("schedule_date") String scheduleDate,
            @JsonProperty("time_slot") String timeSlot,
            @JsonProperty("total_slots") int totalSlots,
            @JsonProperty("remaining_slots") int remainingSlots) {}

    public record Coordinates(double latitude, double longitude) {

        public static Coordinates fromNullable(Double latitude, Double longitude) {
            if (latitude == null && longitude == null) {
                return null;
            }
            if (latitude == null || longitude == null) {
                throw new ApiException(400, "lat 与 lng 必须同时提供");
            }
            return new Coordinates(latitude, longitude);
        }
    }
}
