package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Medication;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MedicationMapper extends BaseMapper<Medication> {
    @Select("SELECT * FROM medications WHERE is_active = TRUE ORDER BY name")
    List<Medication> selectActive();
}
