package com.zhiyu.health.mapper.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.organization.HospitalCampus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HospitalCampusMapper extends BaseMapper<HospitalCampus> {

    /**
     * 动态聚合服务城市（票 49）：城市只来自院区数据，任何查询不得写死城市。
     * 有坐标时按「该城市最近院区距离」排序，无坐标时距离为 NULL、回退 city_code 稳定序。
     *
     * Haversine 球面距离：6371km 为地球半径。acos 自变量用 LEAST 钳到 [-1,1]，
     * 防止浮点误差把 acos(>1) 置为 NULL 丢失整行（距离越近越容易触发）。
     * PostgreSQL 的 LEAST 忽略 NULL 参数，无坐标时表达式会错误算成 acos(1.0)=0，
     * 故距离统一由 CASE WHEN 在坐标参数为 NULL 时显式置 NULL，MIN 聚合后仍为 NULL。
     * 不引入 PostGIS，纯 SQL 在演示数据规模下足够（票 06 硬约束）。
     */
    @Select(
            """
            SELECT city_code, city_name,
                   MIN(CASE WHEN #{latitude,jdbcType=DOUBLE} IS NULL THEN NULL
                            ELSE 6371 * acos(LEAST(1.0,
                                sin(radians(latitude)) * sin(radians(#{latitude,jdbcType=DOUBLE}))
                              + cos(radians(latitude)) * cos(radians(#{latitude,jdbcType=DOUBLE}))
                              * cos(radians(#{longitude,jdbcType=DOUBLE} - longitude))
                       )) END) AS distance_km
            FROM hospital_campuses
            GROUP BY city_code, city_name
            ORDER BY distance_km ASC NULLS LAST, city_code
            """)
    List<ServiceCityRow> selectServiceCities(@Param("longitude") Double longitude, @Param("latitude") Double latitude);

    /**
     * 当前城市医院列表（票 49）：city_code 是硬筛选边界，不跨城市兜底。
     * DISTINCT ON 按医院去重、保留距离最近的院区；无坐标时距离为 NULL，回退医院 id 稳定序。
     * record 构造器顺序须与 SELECT 列顺序一致。
     */
    @Select(
            """
            SELECT hospital_id, name, level, campus_id, campus_name, distance_km
            FROM (
                SELECT DISTINCT ON (h.id)
                       h.id AS hospital_id, h.name, h.level,
                       c.id AS campus_id, c.name AS campus_name,
                       CASE WHEN #{latitude,jdbcType=DOUBLE} IS NULL THEN NULL
                            ELSE 6371 * acos(LEAST(1.0,
                                sin(radians(c.latitude)) * sin(radians(#{latitude,jdbcType=DOUBLE}))
                              + cos(radians(c.latitude)) * cos(radians(#{latitude,jdbcType=DOUBLE}))
                              * cos(radians(#{longitude,jdbcType=DOUBLE} - c.longitude))
                       )) END AS distance_km
                FROM hospitals h
                JOIN hospital_campuses c ON c.hospital_id = h.id
                WHERE c.city_code = #{cityCode}
                ORDER BY h.id, distance_km ASC NULLS LAST, c.id
            ) nearest
            ORDER BY distance_km ASC NULLS LAST, hospital_id
            """)
    List<HospitalWithNearestCampus> selectHospitalsByCity(
            @Param("cityCode") String cityCode,
            @Param("longitude") Double longitude,
            @Param("latitude") Double latitude);

    /** 医院院区列表：按距离排序，无坐标时距离为 NULL、回退院区 id 稳定序。 */
    @Select(
            """
            SELECT c.id AS campus_id, c.name, c.address,
                   CASE WHEN #{latitude,jdbcType=DOUBLE} IS NULL THEN NULL
                        ELSE 6371 * acos(LEAST(1.0,
                            sin(radians(c.latitude)) * sin(radians(#{latitude,jdbcType=DOUBLE}))
                          + cos(radians(c.latitude)) * cos(radians(#{latitude,jdbcType=DOUBLE}))
                          * cos(radians(#{longitude,jdbcType=DOUBLE} - c.longitude))
                   )) END AS distance_km
            FROM hospital_campuses c
            WHERE c.hospital_id = #{hospitalId}
            ORDER BY distance_km ASC NULLS LAST, c.id
            """)
    List<CampusWithDistance> selectCampusesByHospital(
            @Param("hospitalId") long hospitalId,
            @Param("longitude") Double longitude,
            @Param("latitude") Double latitude);

    /** Agent 就近推荐（票 49 迁至院区粒度）：地址为院区地址，附带院区名供端侧区分同医院多院区。 */
    @Select(
            """
            SELECT c.id AS campus_id, c.hospital_id, h.name AS hospital_name, h.level,
                   c.name AS campus_name, c.address,
                   6371 * acos(LEAST(1.0,
                       sin(radians(c.latitude)) * sin(radians(#{latitude}))
                     + cos(radians(c.latitude)) * cos(radians(#{latitude}))
                     * cos(radians(#{longitude} - c.longitude))
                   )) AS distance_km
            FROM hospital_campuses c
            JOIN hospitals h ON h.id = c.hospital_id
            WHERE c.longitude IS NOT NULL AND c.latitude IS NOT NULL
            ORDER BY distance_km ASC, c.id
            """)
    List<CampusRecommendationRow> selectNearby(
            @Param("longitude") double longitude, @Param("latitude") double latitude);

    /** 服务城市投影行：record 构造器顺序须与 SELECT 列顺序一致。 */
    record ServiceCityRow(String cityCode, String cityName, Double distanceKm) {}

    /** 医院 + 最近院区投影行。 */
    record HospitalWithNearestCampus(
            long hospitalId, String name, String level, long campusId, String campusName, Double distanceKm) {}

    /** 院区 + 距离投影行。 */
    record CampusWithDistance(long campusId, String name, String address, Double distanceKm) {}

    /** Agent 就近推荐投影行（院区粒度）。 */
    record CampusRecommendationRow(
            long campusId,
            long hospitalId,
            String hospitalName,
            String level,
            String campusName,
            String address,
            double distanceKm) {}
}
