package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.entity.HospitalCampus;
import com.zhiyu.health.mapper.HospitalCampusMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端医院管理：CRUD 由 ServiceImpl 提供，404 判定留在 service 层（ApiExceptionHandler 统一出口）。 */
@Service
@RequiredArgsConstructor
public class HospitalAdminService extends ServiceImpl<HospitalMapper, Hospital> {

    private final HospitalCampusMapper hospitalCampusMapper;

    public List<Hospital> listAll() {
        return list(new QueryWrapper<Hospital>().orderByAsc("id"));
    }

    public Hospital create(Hospital hospital) {
        save(hospital);
        return hospital;
    }

    public Hospital update(Hospital hospital) {
        if (getById(hospital.getId()) == null) {
            throw new ApiException(404, "医院不存在");
        }
        updateById(hospital);
        return getById(hospital.getId());
    }

    public void delete(long hospitalId) {
        if (getById(hospitalId) == null) {
            throw new ApiException(404, "医院不存在");
        }
        // 全链限制删除（票 49）：院区的地址/号源都挂在医院下，删医院会使其成为孤儿数据，
        // DB 外键也是限制删除，这里提前返回明确 409 而不是裸露的 FK 错误。
        Long campuses = hospitalCampusMapper.selectCount(
                Wrappers.<HospitalCampus>lambdaQuery().eq(HospitalCampus::getHospitalId, hospitalId));
        if (campuses != null && campuses > 0) {
            throw new ApiException(409, "医院下存在院区，无法删除");
        }
        removeById(hospitalId);
    }
}
