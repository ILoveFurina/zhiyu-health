package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Hospital;
import com.zhiyu.health.mapper.HospitalMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/** B 端医院管理：CRUD 由 ServiceImpl 提供，404 判定留在 service 层（ApiExceptionHandler 统一出口）。 */
@Service
public class HospitalAdminService extends ServiceImpl<HospitalMapper, Hospital> {

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
        if (!removeById(hospitalId)) {
            throw new ApiException(404, "医院不存在");
        }
    }
}
