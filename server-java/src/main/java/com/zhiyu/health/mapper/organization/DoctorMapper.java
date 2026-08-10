package com.zhiyu.health.mapper.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.organization.Doctor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DoctorMapper extends BaseMapper<Doctor> {

    // 开方来源院区派生（票 88）：医生当前所属院区经 科室 → 院区 外键取得，禁止客户端传入。
    @Select(
            "SELECT dep.campus_id FROM doctors d JOIN departments dep ON dep.id = d.department_id WHERE d.id = #{doctorId}")
    Long selectCampusIdByDoctorId(@Param("doctorId") long doctorId);
}
