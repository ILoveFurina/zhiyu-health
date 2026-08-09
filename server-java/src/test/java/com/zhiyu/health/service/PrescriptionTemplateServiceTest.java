package com.zhiyu.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.PrescriptionTemplate;
import com.zhiyu.health.entity.prescription.PrescriptionTemplateItem;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionTemplateItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionTemplateMapper;
import com.zhiyu.health.service.prescription.PrescriptionTemplateService;
import com.zhiyu.health.service.prescription.mapping.PrescriptionTemplateDtoMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PrescriptionTemplateServiceTest {

    private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final PrescriptionTemplateMapper templateMapper = mock(PrescriptionTemplateMapper.class);
    private final PrescriptionTemplateItemMapper itemMapper = mock(PrescriptionTemplateItemMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final PrescriptionTemplateService service = new PrescriptionTemplateService(
            staffUserMapper,
            medicationMapper,
            templateMapper,
            itemMapper,
            transactionTemplate,
            Mappers.getMapper(PrescriptionTemplateDtoMapper.class));

    @Test
    void listScopesByCurrentDoctor() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(1L));
        when(templateMapper.selectList(any())).thenReturn(List.of(template(1L, 1L)));
        when(itemMapper.selectDetailed(1L)).thenReturn(List.of());

        List<PrescriptionTemplateService.TemplateView> views = service.listTemplates(8L);

        assertEquals(1, views.size());
        // 列表查询必须以当前医生 doctor_id 作为 WHERE 条件，隔离他人模板。
        // eq 条件值走参数占位符，实际绑定值在 paramNameValuePairs 中。
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(templateMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("doctor_id"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(1L));
    }

    @Test
    void detailRejectsForeignTemplate() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(1L));
        when(templateMapper.selectById(3L)).thenReturn(template(3L, 2L));

        ApiException error = assertThrows(ApiException.class, () -> service.getDetail(8L, 3L));

        assertEquals(404, error.getStatus());
        verifyNoInteractions(itemMapper);
    }

    @Test
    void updateRejectsForeignTemplateBeforeWrite() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(1L));
        when(templateMapper.selectById(3L)).thenReturn(template(3L, 2L));

        ApiException error =
                assertThrows(ApiException.class, () -> service.update(8L, 3L, command(List.of(itemInput(12L)))));

        assertEquals(404, error.getStatus());
        verify(templateMapper, never()).updateById(any(PrescriptionTemplate.class));
        verifyNoInteractions(itemMapper, transactionTemplate);
    }

    @Test
    void deleteRejectsForeignTemplate() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(1L));
        when(templateMapper.selectById(3L)).thenReturn(template(3L, 2L));

        ApiException error = assertThrows(ApiException.class, () -> service.delete(8L, 3L));

        assertEquals(404, error.getStatus());
        verify(templateMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void createRejectsInactiveMedication() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(1L));
        Medication inactive = new Medication();
        inactive.setId(12L);
        inactive.setIsActive(false);
        when(medicationMapper.selectById(12L)).thenReturn(inactive);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.create(new PrescriptionTemplateService.SaveCommand(8L, "测试", List.of(itemInput(12L)))));

        assertEquals(400, error.getStatus());
        verify(templateMapper, never()).insert(any(PrescriptionTemplate.class));
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void createRejectsNonDoctor() {
        StaffUser admin = new StaffUser();
        admin.setRole(StaffUser.ROLE_ADMIN);
        when(staffUserMapper.selectById(1L)).thenReturn(admin);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.create(new PrescriptionTemplateService.SaveCommand(1L, "测试", List.of(itemInput(12L)))));

        assertEquals(403, error.getStatus());
        verifyNoInteractions(templateMapper, itemMapper);
    }

    @Test
    void createPersistsTemplateAndItemsInTransaction() {
        when(staffUserMapper.selectById(8L)).thenReturn(doctor(1L));
        when(medicationMapper.selectById(12L)).thenReturn(activeMedication(12L));
        // 测试替身直接执行事务回调，等价于真实事务模板的行为。
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        doAnswer(invocation -> {
                    invocation.getArgument(0, PrescriptionTemplate.class).setId(1L);
                    return 1;
                })
                .when(templateMapper)
                .insert(any(PrescriptionTemplate.class));
        when(templateMapper.selectById(1L)).thenReturn(template(1L, 1L));
        when(itemMapper.selectDetailed(1L)).thenReturn(List.of());

        PrescriptionTemplateService.TemplateView view =
                service.create(new PrescriptionTemplateService.SaveCommand(8L, " 高血压基础用药 ", List.of(itemInput(12L))));

        assertEquals(1L, view.id());
        verify(itemMapper).insert(any(PrescriptionTemplateItem.class));
        ArgumentCaptor<PrescriptionTemplate> captor = ArgumentCaptor.forClass(PrescriptionTemplate.class);
        verify(templateMapper).insert(captor.capture());
        // 名称 trim 后落库，doctor_id 来自登录医生而非请求体。
        assertEquals("高血压基础用药", captor.getValue().getName());
        assertEquals(1L, captor.getValue().getDoctorId());
    }

    private PrescriptionTemplateService.SaveCommand command(List<PrescriptionTemplateService.ItemInput> items) {
        return new PrescriptionTemplateService.SaveCommand(8L, "随访用药", items);
    }

    private PrescriptionTemplateService.ItemInput itemInput(long medicationId) {
        return new PrescriptionTemplateService.ItemInput(medicationId, "5mg", "每日1次", "30天", null);
    }

    private StaffUser doctor(long doctorId) {
        StaffUser staff = new StaffUser();
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(doctorId);
        return staff;
    }

    private Medication activeMedication(long id) {
        Medication medication = new Medication();
        medication.setId(id);
        medication.setIsActive(true);
        return medication;
    }

    private PrescriptionTemplate template(long id, long doctorId) {
        PrescriptionTemplate template = new PrescriptionTemplate();
        template.setId(id);
        template.setDoctorId(doctorId);
        template.setName("模板" + id);
        return template;
    }
}
