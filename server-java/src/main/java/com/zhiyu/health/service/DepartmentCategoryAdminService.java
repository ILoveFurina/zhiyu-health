package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.Department;
import com.zhiyu.health.entity.DepartmentCategory;
import com.zhiyu.health.mapper.DepartmentCategoryMapper;
import com.zhiyu.health.mapper.DepartmentMapper;
import com.zhiyu.health.mapper.HospitalMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端医院科室分类管理（票 49）：CRUD 由 ServiceImpl 提供；医院外键写入前校验，删除受科室引用限制。 */
@Service
@RequiredArgsConstructor
public class DepartmentCategoryAdminService extends ServiceImpl<DepartmentCategoryMapper, DepartmentCategory> {

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;

    public List<DepartmentCategory> listAll() {
        return list(new QueryWrapper<DepartmentCategory>().orderByAsc("hospital_id", "sort_order", "id"));
    }

    public DepartmentCategory create(DepartmentCategory category) {
        if (hospitalMapper.selectById(category.getHospitalId()) == null) {
            throw new ApiException(404, "医院不存在");
        }
        save(category);
        return category;
    }

    public DepartmentCategory update(DepartmentCategory category) {
        if (getById(category.getId()) == null || hospitalMapper.selectById(category.getHospitalId()) == null) {
            throw new ApiException(404, "科室分类或医院不存在");
        }
        updateById(category);
        return getById(category.getId());
    }

    public void delete(long categoryId) {
        if (getById(categoryId) == null) {
            throw new ApiException(404, "科室分类不存在");
        }
        // 全链限制删除（票 49）：分类下存在实际科室即拒绝删除。
        Long departments = departmentMapper.selectCount(
                Wrappers.<Department>lambdaQuery().eq(Department::getCategoryId, categoryId));
        if (departments != null && departments > 0) {
            throw new ApiException(409, "科室分类下存在科室，无法删除");
        }
        removeById(categoryId);
    }
}
