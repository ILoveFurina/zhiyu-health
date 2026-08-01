import { ModalForm, ProFormDigit, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { createDoctor, listDepartments, updateDoctor, type Doctor } from '@/services/organization';

interface Props {
  open: boolean;
  record?: Doctor;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function DoctorForm({ open, record, onOpenChange, onSuccess }: Props) {
  return (
    <ModalForm<Omit<Doctor, 'id'>>
      key={record?.id}
      title={record ? '编辑医生' : '新建医生'}
      open={open}
      onOpenChange={onOpenChange}
      initialValues={record}
      modalProps={{ destroyOnHidden: true, forceRender: true }}
      onFinish={async (values) => {
        if (record) {
          await updateDoctor(record.id, values);
        } else {
          await createDoctor(values);
        }
        onSuccess();
        return true;
      }}
    >
      <ProFormSelect
        name="department_id"
        label="所属科室"
        rules={[{ required: true, message: '请选择所属科室' }]}
        request={async () => (await listDepartments()).map((d) => ({ label: d.name, value: d.id }))}
      />
      <ProFormText name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]} />
      <ProFormText name="title" label="职称" rules={[{ required: true, message: '请输入职称' }]} />
      <ProFormDigit
        name="registration_fee"
        label="挂号费(元)"
        min={0}
        fieldProps={{ precision: 2 }}
        rules={[{ required: true, message: '请输入挂号费' }]}
      />
      <ProFormText name="specialty" label="擅长" rules={[{ required: true, message: '请输入擅长' }]} />
      <ProFormText name="photo_url" label="照片 URL" />
    </ModalForm>
  );
}
