package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.HealthProfileAllergy;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HealthProfileAllergyMapper extends BaseMapper<HealthProfileAllergy> {

    @Select("SELECT allergen FROM health_profile_allergies WHERE health_profile_id = #{profileId} ORDER BY id")
    List<String> selectAllergens(@Param("profileId") long profileId);
}
