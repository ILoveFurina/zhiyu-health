package com.zhiyu.health.service.prescription;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.PrescriptionTemplate;
import com.zhiyu.health.entity.prescription.PrescriptionTemplateItem;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionTemplateItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionTemplateMapper;
import com.zhiyu.health.service.prescription.mapping.PrescriptionTemplateDtoMapper;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** B 端处方模板（票 47）：按 doctor_id 归属，所有入口先 requireDoctor 再以 doctor_id 限定，跨医生一律 404。 */
@Service
@RequiredArgsConstructor
public class PrescriptionTemplateService extends ServiceImpl<PrescriptionTemplateMapper, PrescriptionTemplate> {

    private final StaffUserMapper staffUserMapper;
    private final MedicationMapper medicationMapper;
    private final PrescriptionTemplateMapper templateMapper;
    private final PrescriptionTemplateItemMapper itemMapper;
    private final TransactionTemplate transactionTemplate;
    private final PrescriptionTemplateDtoMapper dtoMapper;

    public List<TemplateView> listTemplates(long staffId) {
        long doctorId = requireDoctor(staffId);
        return templateMapper
                .selectList(new QueryWrapper<PrescriptionTemplate>()
                        .eq("doctor_id", doctorId)
                        .orderByAsc("id"))
                .stream()
                .map(template -> toView(template, itemMapper.selectDetailed(template.getId())))
                .toList();
    }

    public TemplateView getDetail(long staffId, long id) {
        long doctorId = requireDoctor(staffId);
        PrescriptionTemplate template = requireOwned(id, doctorId);
        return toView(template, itemMapper.selectDetailed(id));
    }

    public TemplateView create(SaveCommand command) {
        long doctorId = requireDoctor(command.staffId());
        validateItems(command.items());
        Long id = transactionTemplate.execute(status -> {
            PrescriptionTemplate template = dtoMapper.toTemplate(command, doctorId);
            templateMapper.insert(template);
            for (ItemInput input : command.items()) {
                itemMapper.insert(dtoMapper.toItem(input, template.getId()));
            }
            return template.getId();
        });
        return toView(templateMapper.selectById(id), itemMapper.selectDetailed(id));
    }

    public TemplateView update(long staffId, long id, SaveCommand command) {
        long doctorId = requireDoctor(staffId);
        requireOwned(id, doctorId);
        validateItems(command.items());
        transactionTemplate.executeWithoutResult(status -> {
            PrescriptionTemplate template = dtoMapper.toTemplate(command, doctorId);
            template.setId(id);
            templateMapper.updateById(template);
            // 明细整体删插：模板明细无外部引用，增删改条目无需逐项对账，同事务保证主表与明细一致。
            itemMapper.delete(new QueryWrapper<PrescriptionTemplateItem>().eq("template_id", id));
            for (ItemInput input : command.items()) {
                itemMapper.insert(dtoMapper.toItem(input, id));
            }
        });
        return toView(templateMapper.selectById(id), itemMapper.selectDetailed(id));
    }

    public void delete(long staffId, long id) {
        long doctorId = requireDoctor(staffId);
        requireOwned(id, doctorId);
        // 明细由 prescription_template_items 的 ON DELETE CASCADE 兜底清理。
        templateMapper.deleteById(id);
    }

    private PrescriptionTemplate requireOwned(long id, long doctorId) {
        PrescriptionTemplate template = templateMapper.selectById(id);
        // 跨医生访问与不存在统一 404，不暴露他人模板的存在性。
        if (template == null || !template.getDoctorId().equals(doctorId)) {
            throw new ApiException(404, "处方模板不存在");
        }
        return template;
    }

    private void validateItems(List<ItemInput> items) {
        for (ItemInput item : items) {
            Medication medication = medicationMapper.selectById(item.medicationId());
            if (medication == null || !Boolean.TRUE.equals(medication.getIsActive())) {
                throw new ApiException(400, "药品不存在或已停用");
            }
        }
    }

    /** 与 PrescriptionService 等价的医生身份解析：仅绑定医生的 staff 可操作。 */
    private long requireDoctor(long staffId) {
        StaffUser staff = staffUserMapper.selectById(staffId);
        if (staff == null || !StaffUser.ROLE_DOCTOR.equals(staff.getRole()) || staff.getDoctorId() == null) {
            throw new ApiException(403, "仅医生可操作");
        }
        return staff.getDoctorId();
    }

    private TemplateView toView(PrescriptionTemplate template, List<PrescriptionTemplateItem> items) {
        return dtoMapper.toTemplateView(template, dtoMapper.toItemViews(items));
    }

    public record ItemInput(long medicationId, String dosage, String frequency, String duration, String notes) {}

    public record SaveCommand(long staffId, String name, List<ItemInput> items) {}

    public record ItemView(
            Long id,
            Long medicationId,
            String medicationName,
            String specification,
            String dosage,
            String frequency,
            String duration,
            String notes) {}

    public record TemplateView(Long id, String name, Long doctorId, OffsetDateTime createdAt, List<ItemView> items) {}
}
