import { ModalForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import {
  createDepartment,
  listCampuses,
  listDepartmentCategories,
  listHospitals,
  listStandardDepartments,
  updateDepartment,
  type Department,
} from '@/services/organization';

interface Props {
  open: boolean;
  record?: Department;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function DepartmentForm({ open, record, onOpenChange, onSuccess }: Props) {
  return (
    <ModalForm<Omit<Department, 'id'>>
      title={record ? '编辑科室' : '新建科室'}
      open={open}
      onOpenChange={onOpenChange}
      initialValues={record}
      modalProps={{ destroyOnClose: true, forceRender: true }}
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
        rules={[{ required: true, message: '请选择所属院区' }]}
        request={async () => {
          const [campusList, hospitalList] = await Promise.all([listCampuses(), listHospitals()]);
          return campusList.map((c) => {
            const hospitalName = hospitalList.find((h) => h.id === c.hospital_id)?.name;
            return { label: hospitalName ? `${hospitalName}-${c.name}` : c.name, value: c.id };
          });
        }}
      />
      <ProFormSelect
        name="category_id"
        label="科室分类"
        rules={[{ required: true, message: '请选择科室分类' }]}
        request={async () => (await listDepartmentCategories()).map((c) => ({ label: c.name, value: c.id }))}
      />
      <ProFormSelect
        name="standard_department_id"
        label="标准科室"
        rules={[{ required: true, message: '请选择标准科室' }]}
        request={async () =>
          (await listStandardDepartments()).map((s) => ({ label: `${s.category}-${s.name}`, value: s.id }))
        }
      />
      <ProFormText name="name" label="科室名称" rules={[{ required: true, message: '请输入科室名称' }]} />
      <ProFormText name="floor" label="楼层" rules={[{ required: true, message: '请输入楼层' }]} />
      <ProFormText name="location" label="位置" rules={[{ required: true, message: '请输入位置' }]} />
    </ModalForm>
  );
}
