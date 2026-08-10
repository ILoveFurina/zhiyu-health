package com.zhiyu.health.mapper.pharmacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.entity.pharmacy.PharmacyProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CampusPharmacyMapper extends BaseMapper<CampusPharmacy> {

    @Select("SELECT * FROM campus_pharmacies WHERE campus_id = #{campusId}")
    CampusPharmacy selectByCampusId(@Param("campusId") long campusId);

    String PROFILE_COLUMNS =
            """
            SELECT cp.id AS pharmacy_id, cp.campus_id, cp.display_name, cp.delivery_fee,
                   cp.estimated_delivery_minutes,
                   h.name AS hospital_name, hc.name AS campus_name, hc.address AS campus_address,
                   hc.city_name, hc.longitude AS campus_longitude, hc.latitude AS campus_latitude
            FROM campus_pharmacies cp
            JOIN hospital_campuses hc ON hc.id = cp.campus_id
            JOIN hospitals h ON h.id = hc.hospital_id
            """;

    // 下单履约快照与购药预览取数（票 88）：药房 + 医院/院区/地址/城市一次带出。
    @Select(PROFILE_COLUMNS + " WHERE cp.id = #{pharmacyId}")
    PharmacyProfile selectProfileById(@Param("pharmacyId") long pharmacyId);

    @Select(PROFILE_COLUMNS + " WHERE cp.campus_id = #{campusId}")
    PharmacyProfile selectProfileByCampusId(@Param("campusId") long campusId);
}
