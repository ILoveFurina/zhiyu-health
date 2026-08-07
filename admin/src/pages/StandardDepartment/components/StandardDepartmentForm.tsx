import { ModalForm, ProFormDigit, ProFormText } from '@ant-design/pro-components';
import { Form } from 'antd';
import {
  createStandardDepartment,
  updateStandardDepartment,
  type StandardDepartment,
} from '@/services/organization';

interface Props {
  open: boolean;
  record?: StandardDepartment;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function StandardDepartmentForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<Omit<StandardDepartment, 'id'>>();

  return (
    <ModalForm<Omit<StandardDepartment, 'id'>>
      form={form}
      title={record ? '编辑标准科室' : '新建标准科室'}
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
          await updateStandardDepartment(record.id, values);
        } else {
          await createStandardDepartment(values);
        }
        onSuccess();
        return true;
      }}
    >
      <ProFormText name="category" label="科类" rules={[{ required: true, message: '请输入科类' }]} />
      <ProFormText name="name" label="标准科室名称" rules={[{ required: true, message: '请输入标准科室名称' }]} />
      <ProFormDigit name="sort_order" label="排序" rules={[{ required: true, message: '请输入排序' }]} />
    </ModalForm>
  );
}
