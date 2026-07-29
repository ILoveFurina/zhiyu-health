package com.zhiyu.health.service;

import com.zhiyu.health.mapper.HospitalMapper;
import com.zhiyu.health.mapper.HospitalMapper.HospitalWithDistance;
import org.springframework.stereotype.Service;

import java.util.List;

/** 为 Agent 业务工具提供按距离排序的就近医院推荐，只读取 PostgreSQL 业务数据。 */
@Service
public class HospitalRecommendationService {

    private final HospitalMapper hospitalMapper;

    public HospitalRecommendationService(HospitalMapper hospitalMapper) {
        this.hospitalMapper = hospitalMapper;
    }

    public List<HospitalRecommendation> recommendNearby(double longitude, double latitude) {
        return hospitalMapper.selectNearby(longitude, latitude).stream()
                .map(this::toRecommendation)
                .toList();
    }

    private HospitalRecommendation toRecommendation(HospitalWithDistance hospital) {
        return new HospitalRecommendation(
                hospital.id(),
                hospital.name(),
                hospital.level(),
                hospital.address(),
                hospital.distanceKm());
    }

    public record HospitalRecommendation(
            long hospitalId,
            String name,
            String level,
            String address,
            double distanceKm) {
    }
}
