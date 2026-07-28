import { ModalForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { createDepartment, listHospitals, updateDepartment, type Department } from '@/services/organization';

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
        name="hospital_id"
        label="所属医院"
        rules={[{ required: true, message: '请选择所属医院' }]}
        request={async () => (await listHospitals()).map((h) => ({ label: h.name, value: h.id }))}
      />
      <ProFormText name="name" label="科室名称" rules={[{ required: true, message: '请输入科室名称' }]} />
      <ProFormText name="floor" label="楼层" rules={[{ required: true, message: '请输入楼层' }]} />
      <ProFormText name="location" label="位置" rules={[{ required: true, message: '请输入位置' }]} />
    </ModalForm>
  );
}
