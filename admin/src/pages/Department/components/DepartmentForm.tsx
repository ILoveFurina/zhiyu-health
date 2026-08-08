import { ModalForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { Form } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  createDepartment,
  listCampuses,
  listDepartmentCategories,
  listHospitals,
  listStandardDepartments,
  updateDepartment,
  type Campus,
  type Department,
  type DepartmentCategory,
  type Hospital,
  type StandardDepartment,
} from '@/services/organization';

interface Props {
  open: boolean;
  record?: Department;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function DepartmentForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<Omit<Department, 'id'>>();
  // 表单内本地缓存院区/分类/医院/标准科室，用于按所属院区联动过滤分类下拉，自然消除重复
  const [campuses, setCampuses] = useState<Campus[]>([]);
  const [categories, setCategories] = useState<DepartmentCategory[]>([]);
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [standardDepartments, setStandardDepartments] = useState<StandardDepartment[]>([]);

  useEffect(() => {
    listCampuses().then(setCampuses).catch(() => {});
    listDepartmentCategories().then(setCategories).catch(() => {});
    listHospitals().then(setHospitals).catch(() => {});
    listStandardDepartments().then(setStandardDepartments).catch(() => {});
  }, []);

  // 院区下拉：拼出「医院-院区」label
  const campusOptions = useMemo(
    () =>
      campuses.map((c) => {
        const hospitalName = hospitals.find((h) => h.id === c.hospital_id)?.name;
        return { label: hospitalName ? `${hospitalName}-${c.name}` : c.name, value: c.id };
      }),
    [campuses, hospitals],
  );

  // 科室分类下拉：分类已挂院区，选院区后直接按 campus_id 过滤，自然消除多家医院同名分类重复
  const selectedCampusId = Form.useWatch('campus_id', form);
  const categoryOptions = useMemo(
    () =>
      categories
        .filter((c) => (selectedCampusId ? c.campus_id === selectedCampusId : true))
        .map((c) => ({ label: c.name, value: c.id })),
    [categories, selectedCampusId],
  );

  // 院区切换后，若当前分类已不属于新院区，清空以免提交错配的分类
  useEffect(() => {
    if (!selectedCampusId) return;
    const currentCat = form.getFieldValue('category_id');
    if (currentCat != null && !categoryOptions.some((o) => o.value === currentCat)) {
      form.setFieldValue('category_id', undefined);
    }
  }, [selectedCampusId, categoryOptions, form]);

  // 标准科室下拉：按所选分类的科类名联动过滤（分类 name 已收敛为标准科类名，如"内科"）
  const selectedCategoryId = Form.useWatch('category_id', form);
  const selectedCategoryName = useMemo(() => {
    if (!selectedCategoryId) return undefined;
    return categories.find((c) => c.id === selectedCategoryId)?.name;
  }, [categories, selectedCategoryId]);

  const standardOptions = useMemo(() => {
    const matched = standardDepartments.filter((s) =>
      selectedCategoryName ? s.category === selectedCategoryName : true,
    );
    return matched.map((s) => ({ label: `${s.category}-${s.name}`, value: s.id }));
  }, [standardDepartments, selectedCategoryName]);

  // 分类切换后，若当前标准科室已不属于新分类，清空以免提交错配的标准科室
  useEffect(() => {
    if (!selectedCategoryId) return;
    const currentStd = form.getFieldValue('standard_department_id');
    if (currentStd != null && !standardOptions.some((o) => o.value === currentStd)) {
      form.setFieldValue('standard_department_id', undefined);
    }
  }, [selectedCategoryId, standardOptions, form]);

  return (
    <ModalForm<Omit<Department, 'id'>>
      form={form}
      title={record ? '编辑科室' : '新建科室'}
      open={open}
      onOpenChange={(o) => {
        if (o) {
          form.setFieldsValue(record ?? {});
        } else {
          form.resetFields();
        }
        onOpenChange(o);
      }}
      modalProps={{ destroyOnClose: false }}
      onFinish={async (values) => {
        if (record) {
          await updateDepartment(record.id, values);
        } else {
          await createDepartment(values);
        }
        onSuccess();
        return true;
      }}
    >
      <ProFormSelect
        name="campus_id"
        label="所属院区"
        options={campusOptions}
        rules={[{ required: true, message: '请选择所属院区' }]}
        placeholder="请先选择所属院区"
      />
      <ProFormSelect
        name="category_id"
        label="科室分类"
        options={categoryOptions}
        rules={[{ required: true, message: '请选择科室分类' }]}
        placeholder={selectedCampusId ? '请选择科室分类' : '请先选择所属院区'}
        disabled={!selectedCampusId}
      />
      <ProFormSelect
        name="standard_department_id"
        label="标准科室"
        options={standardOptions}
        rules={[{ required: true, message: '请选择标准科室' }]}
        placeholder={selectedCategoryId ? '请选择标准科室' : '请先选择科室分类'}
        disabled={!selectedCategoryId}
      />
      <ProFormText name="name" label="科室名称" rules={[{ required: true, message: '请输入科室名称' }]} />
      <ProFormText name="floor" label="楼层" rules={[{ required: true, message: '请输入楼层' }]} />
      <ProFormText name="location" label="位置" rules={[{ required: true, message: '请输入位置' }]} />
    </ModalForm>
  );
}
