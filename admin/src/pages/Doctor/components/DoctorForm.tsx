import { ModalForm, ProFormDatePicker, ProFormDigit, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { Form } from 'antd';
import { createDoctor, listDepartments, updateDoctor, type Doctor } from '@/services/organization';

interface Props {
  open: boolean;
  record?: Doctor;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function DoctorForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<Omit<Doctor, 'id'>>();

  return (
    <ModalForm<Omit<Doctor, 'id'>>
      form={form}
      title={record ? '编辑医生' : '新建医生'}
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
      <ProFormSelect
        name="gender"
        label="性别"
        options={[{ label: '男', value: '男' }, { label: '女', value: '女' }]}
        rules={[{ required: true, message: '请选择性别' }]}
      />
      <ProFormDatePicker
        name="birth_date"
        label="出生日期"
        fieldProps={{ style: { width: '100%' } }}
        rules={[{ required: true, message: '请选择出生日期' }]}
      />
      <ProFormText name="title" label="职称" rules={[{ required: true, message: '请输入职称' }]} />
      <ProFormDigit
        name="registration_fee"
        label="挂号费(元)"
        min={0}
        fieldProps={{ precision: 2 }}
        rules={[{ required: true, message: '请输入挂号费' }]}
      />
      <ProFormText name="specialty" label="擅长" rules={[{ required: true, message: '请输入擅长' }]} />
      <ProFormText name="photo_url" label="照片" placeholder="暂填图片 URL，后续将改为上传" />
    </ModalForm>
  );
}
