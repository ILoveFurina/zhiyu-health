package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Hospital;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HospitalMapper extends BaseMapper<Hospital> {

    /**
     * 按经纬度距离升序查询医院并返回直线距离（公里）。
     *
     * Haversine 球面距离：6371km 为地球半径。acos 自变量用 LEAST 钳到 [-1,1]，
     * 防止浮点误差把 acos(>1) 置为 NULL 丢失整行（距离越近越容易触发）。
     * 不引入 PostGIS，纯 SQL 在演示数据规模下足够（票 06 硬约束）。
     */
    @Select("""
            SELECT id, name, level, address, longitude, latitude,
                   6371 * acos(LEAST(1.0,
                       sin(radians(latitude)) * sin(radians(#{latitude}))
                     + cos(radians(latitude)) * cos(radians(#{latitude}))
                       * cos(radians(#{longitude} - longitude))
                   )) AS distance_km
            FROM hospitals
            WHERE longitude IS NOT NULL AND latitude IS NOT NULL
            ORDER BY distance_km ASC
            """)
    List<HospitalWithDistance> selectNearby(@Param("longitude") double longitude,
                                            @Param("latitude") double latitude);

    /** 距离投影行：record 构造器顺序须与 SELECT 列顺序一致。 */
    record HospitalWithDistance(
            long id,
            String name,
            String level,
            String address,
            Double longitude,
            Double latitude,
            double distanceKm) {
    }
}
