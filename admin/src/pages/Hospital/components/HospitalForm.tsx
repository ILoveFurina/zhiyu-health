import { ModalForm, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { Form } from 'antd';
import { createHospital, updateHospital, type Hospital } from '@/services/organization';

interface Props {
  open: boolean;
  record?: Hospital;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function HospitalForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<Omit<Hospital, 'id'>>();

  return (
    <ModalForm<Omit<Hospital, 'id'>>
      form={form}
      title={record ? '编辑医院' : '新建医院'}
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
          await updateHospital(record.id, values);
        } else {
          await createHospital(values);
        }
        onSuccess();
        return true;
      }}
    >
      <ProFormText name="name" label="医院名称" rules={[{ required: true, message: '请输入医院名称' }]} />
      <ProFormSelect
        name="level"
        label="等级"
        options={[
          { label: '三甲', value: '三甲' },
          { label: '三乙', value: '三乙' },
        ]}
        rules={[{ required: true, message: '请选择等级' }]}
      />
    </ModalForm>
  );
}
