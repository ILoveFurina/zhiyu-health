package com.zhiyu.health.service.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhiyu.health.entity.pharmacy.PharmacyOtcCatalogRow;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.service.pharmacy.mapping.PharmacyOtcCatalogMapper;
import com.zhiyu.health.service.prescription.DrugOrderService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 药房 OTC 目录（票 95）：C 端只读浏览「哪些院区药房在售哪些 OTC、什么价、有没有货」，
 * 不下单、不预选药房。与 {@link DrugOrderService#otcCandidates} 同口径：请求无 city 入参
 * （demo 单服务城市即全部院区，查询不写死城市）；lng/lat 齐全时按院区真实坐标球面距离升序
 * 并下发 distance_meters（缺坐标院区排最后、距离 null）；无定位保持 SQL 医院/院区稳定序，
 * 不出距离、不伪造距离。
 */
@Service
@RequiredArgsConstructor
public class PharmacyOtcCatalogService {

    private final PharmacyMedicationMapper pharmacyMedicationMapper;
    private final PharmacyOtcCatalogMapper dtoMapper;

    public OtcCatalogView catalog(Double lng, Double lat) {
        boolean located = lng != null && lat != null;
        Map<Long, List<PharmacyOtcCatalogRow>> rowsByPharmacy = new LinkedHashMap<>();
        for (PharmacyOtcCatalogRow row : pharmacyMedicationMapper.selectOtcCatalog()) {
            // 兜底守卫：SQL 已过滤在售 OTC，service 再拦一道，防未来新增调用方绕过过滤条件。
            if (!Boolean.TRUE.equals(row.getIsOnSale()) || Boolean.TRUE.equals(row.getIsPrescription())) {
                continue;
            }
            rowsByPharmacy
                    .computeIfAbsent(row.getPharmacyId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        List<PharmacyGroupView> pharmacies = new ArrayList<>();
        for (List<PharmacyOtcCatalogRow> rows : rowsByPharmacy.values()) {
            PharmacyOtcCatalogRow first = rows.get(0);
            pharmacies.add(new PharmacyGroupView(
                    first.getPharmacyId(),
                    first.getPharmacyDisplayName(),
                    first.getHospitalName(),
                    first.getCampusName(),
                    first.getCampusAddress(),
                    first.getDeliveryFee(),
                    first.getEstimatedDeliveryMinutes(),
                    located
                            ? DrugOrderService.distanceMeters(
                                    lng, lat, first.getCampusLongitude(), first.getCampusLatitude())
                            : null,
                    rows.stream().map(dtoMapper::toItemView).toList()));
        }
        if (located) {
            // 缺坐标院区排最后；同距离保持药房 id 序，结果稳定可重现（与 otcCandidates 同规则）。
            pharmacies.sort(Comparator.comparing(
                            PharmacyGroupView::distanceMeters, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(PharmacyGroupView::pharmacyId));
        }
        return new OtcCatalogView(pharmacies);
    }

    // ---------- 契约记录（snake_case 由 Jackson 全局策略序列化） ----------

    public record OtcCatalogView(List<PharmacyGroupView> pharmacies) {}

    /** 药房分组：无定位时 distance_meters 不序列化（NON_NULL），不伪造距离。 */
    public record PharmacyGroupView(
            Long pharmacyId,
            String pharmacyName,
            String hospitalName,
            String campusName,
            String campusAddress,
            BigDecimal deliveryFee,
            Integer estimatedMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double distanceMeters,
            List<ItemView> items) {}

    /** 目录明细：stock=0 仍下发（前端标「暂时缺货」并禁用行动按钮），停售行不下发。 */
    public record ItemView(
            Long medicationId,
            String name,
            String genericName,
            String specification,
            BigDecimal price,
            Integer stock) {}
}
