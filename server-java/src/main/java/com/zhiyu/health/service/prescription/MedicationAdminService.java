package com.zhiyu.health.service.prescription;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * B 端标准药品目录管理：只读 + 编辑处方属性（票 88 起价格/库存/上下架语义收敛到院区药房），
 * 不新增不删除（spec 0002 决策：新增破坏 Neo4j 禁忌子图对齐，删除破坏历史处方完整性）。
 * 404 判定留在 service 层（ApiExceptionHandler 统一出口）。
 */
@Service
public class MedicationAdminService extends ServiceImpl<MedicationMapper, Medication> {

    public List<Medication> listAll() {
        return list(new QueryWrapper<Medication>().orderByAsc("id"));
    }

    public Medication update(Medication medication) {
        if (getById(medication.getId()) == null) {
            throw new ApiException(404, "药品不存在");
        }
        updateById(medication);
        return getById(medication.getId());
    }
}
