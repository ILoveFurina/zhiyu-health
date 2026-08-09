package com.zhiyu.health.service.organization;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.organization.Doctor;
import com.zhiyu.health.entity.scheduling.Schedule;
import com.zhiyu.health.mapper.organization.DepartmentMapper;
import com.zhiyu.health.mapper.organization.DoctorMapper;
import com.zhiyu.health.mapper.scheduling.ScheduleMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** B 端医生管理：CRUD 由 ServiceImpl 提供；科室外键在写入前校验，缺失即抛 404。 */
@Service
@RequiredArgsConstructor
public class DoctorAdminService extends ServiceImpl<DoctorMapper, Doctor> {

    private final DepartmentMapper departmentMapper;
    private final ScheduleMapper scheduleMapper;

    public List<Doctor> listAll() {
        return list(new QueryWrapper<Doctor>().orderByAsc("id"));
    }

    public Doctor create(Doctor doctor) {
        if (departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            throw new ApiException(404, "科室不存在");
        }
        save(doctor);
        return doctor;
    }

    public Doctor update(Doctor doctor) {
        if (getById(doctor.getId()) == null || departmentMapper.selectById(doctor.getDepartmentId()) == null) {
            throw new ApiException(404, "医生或科室不存在");
        }
        updateById(doctor);
        return getById(doctor.getId());
    }

    public void delete(long doctorId) {
        if (getById(doctorId) == null) {
            throw new ApiException(404, "医生不存在");
        }
        // 全链限制删除（票 49）：医生存在排班即拒绝删除，避免孤儿排班/挂号与 PG FK 裸错。
        Long schedules =
                scheduleMapper.selectCount(Wrappers.<Schedule>lambdaQuery().eq(Schedule::getDoctorId, doctorId));
        if (schedules != null && schedules > 0) {
            throw new ApiException(409, "医生存在排班，无法删除");
        }
        removeById(doctorId);
    }
}
