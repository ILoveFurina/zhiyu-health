import { ModalForm, ProFormDigit, ProFormText } from '@ant-design/pro-components';
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
  return (
    <ModalForm<Omit<StandardDepartment, 'id'>>
      title={record ? '编辑标准科室' : '新建标准科室'}
      open={open}
      onOpenChange={onOpenChange}
      initialValues={record}
      modalProps={{ destroyOnClose: true, forceRender: true }}
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
