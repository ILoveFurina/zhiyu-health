import { ModalForm, ProFormDigit, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { Form } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  createDepartmentCategory,
  listCampuses,
  listHospitals,
  updateDepartmentCategory,
  type Campus,
  type DepartmentCategory,
  type Hospital,
} from '@/services/organization';

interface Props {
  open: boolean;
  record?: DepartmentCategory;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function DepartmentCategoryForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<Omit<DepartmentCategory, 'id'>>();
  // 院区下拉拼「医院-院区」label
  const [campuses, setCampuses] = useState<Campus[]>([]);
  const [hospitals, setHospitals] = useState<Hospital[]>([]);

  useEffect(() => {
    listCampuses().then(setCampuses).catch(() => {});
    listHospitals().then(setHospitals).catch(() => {});
  }, []);

  const campusOptions = useMemo(
    () =>
      campuses.map((c) => {
        const hospitalName = hospitals.find((h) => h.id === c.hospital_id)?.name;
        return { label: hospitalName ? `${hospitalName}-${c.name}` : c.name, value: c.id };
      }),
    [campuses, hospitals],
  );

  return (
    <ModalForm<Omit<DepartmentCategory, 'id'>>
      form={form}
      title={record ? '编辑科室分类' : '新建科室分类'}
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
          await updateDepartmentCategory(record.id, values);
        } else {
          await createDepartmentCategory(values);
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
      />
      <ProFormText name="name" label="分类名称" rules={[{ required: true, message: '请输入分类名称' }]} />
      <ProFormDigit name="sort_order" label="排序" rules={[{ required: true, message: '请输入排序' }]} />
    </ModalForm>
  );
}
