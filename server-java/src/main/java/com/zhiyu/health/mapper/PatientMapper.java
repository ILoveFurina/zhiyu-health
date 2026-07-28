package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Patient;
import org.apache.ibatis.annotations.Mapper;

/** 患者身份 mapper。 */
@Mapper
public interface PatientMapper extends BaseMapper<Patient> {
}
