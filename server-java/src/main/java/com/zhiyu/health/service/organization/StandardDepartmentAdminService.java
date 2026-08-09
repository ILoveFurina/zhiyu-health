package com.zhiyu.health.service.organization;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.Department;
import com.zhiyu.health.entity.organization.StandardDepartment;
import com.zhiyu.health.mapper.organization.DepartmentMapper;
import com.zhiyu.health.mapper.organization.StandardDepartmentMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端平台标准科室管理（票 49）：CRUD 由 ServiceImpl 提供；删除受实际科室映射限制。 */
@Service
@RequiredArgsConstructor
public class StandardDepartmentAdminService extends ServiceImpl<StandardDepartmentMapper, StandardDepartment> {

    private final DepartmentMapper departmentMapper;

    public List<StandardDepartment> listAll() {
        return list(new QueryWrapper<StandardDepartment>().orderByAsc("category", "sort_order", "id"));
    }

    public StandardDepartment create(StandardDepartment standardDepartment) {
        save(standardDepartment);
        return standardDepartment;
    }

    public StandardDepartment update(StandardDepartment standardDepartment) {
        if (getById(standardDepartment.getId()) == null) {
            throw new ApiException(404, "标准科室不存在");
        }
        updateById(standardDepartment);
        return getById(standardDepartment.getId());
    }

    public void delete(long standardDepartmentId) {
        if (getById(standardDepartmentId) == null) {
            throw new ApiException(404, "标准科室不存在");
        }
        // 全链限制删除（票 49）：标准科室是跨医院号源匹配的唯一依据，存在映射即拒绝删除。
        Long mapped = departmentMapper.selectCount(
                Wrappers.<Department>lambdaQuery().eq(Department::getStandardDepartmentId, standardDepartmentId));
        if (mapped != null && mapped > 0) {
            throw new ApiException(409, "标准科室已被实际科室映射，无法删除");
        }
        removeById(standardDepartmentId);
    }
}
