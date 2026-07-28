import { ModalForm, ProFormDigit, ProFormText } from '@ant-design/pro-components';
import { createHospital, updateHospital, type Hospital } from '@/services/organization';

interface Props {
  open: boolean;
  record?: Hospital;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function HospitalForm({ open, record, onOpenChange, onSuccess }: Props) {
  return (
    <ModalForm<Omit<Hospital, 'id'>>
      title={record ? '编辑医院' : '新建医院'}
      open={open}
      onOpenChange={onOpenChange}
      initialValues={record}
      modalProps={{ destroyOnClose: true, forceRender: true }}
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
      <ProFormText name="level" label="等级" rules={[{ required: true, message: '请输入等级' }]} />
      <ProFormText name="address" label="地址" rules={[{ required: true, message: '请输入地址' }]} />
      <ProFormDigit name="longitude" label="经度" rules={[{ required: true, message: '请输入经度' }]} />
      <ProFormDigit name="latitude" label="纬度" rules={[{ required: true, message: '请输入纬度' }]} />
    </ModalForm>
  );
}
