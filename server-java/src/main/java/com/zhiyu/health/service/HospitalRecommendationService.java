package com.zhiyu.health.service;

import com.zhiyu.health.mapper.HospitalCampusMapper;
import com.zhiyu.health.mapper.HospitalCampusMapper.CampusRecommendationRow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 为 Agent 业务工具提供按距离排序的就近医院推荐，只读取 PostgreSQL 业务数据。 */
@Service
@RequiredArgsConstructor
public class HospitalRecommendationService {

    private final HospitalCampusMapper hospitalCampusMapper;

    /**
     * 票 49：医院坐标语义下沉到院区，就近推荐改为院区粒度。
     * 响应字段对 server-py 保持稳定（hospital_id/name/level/address/distance_km），
     * address 为最近院区地址，新增 campus_name 供端侧区分同医院多院区。
     */
    public List<HospitalRecommendation> recommendNearby(double longitude, double latitude) {
        return hospitalCampusMapper.selectNearby(longitude, latitude).stream()
                .map(this::toRecommendation)
                .toList();
    }

    private HospitalRecommendation toRecommendation(CampusRecommendationRow campus) {
        return new HospitalRecommendation(
                campus.hospitalId(),
                campus.hospitalName(),
                campus.level(),
                campus.address(),
                campus.campusName(),
                campus.distanceKm());
    }

    public record HospitalRecommendation(
            long hospitalId, String name, String level, String address, String campusName, double distanceKm) {}
}
