import { ModalForm, ProFormDigit, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import {
  createDepartmentCategory,
  listHospitals,
  updateDepartmentCategory,
  type DepartmentCategory,
} from '@/services/organization';

interface Props {
  open: boolean;
  record?: DepartmentCategory;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function DepartmentCategoryForm({ open, record, onOpenChange, onSuccess }: Props) {
  return (
    <ModalForm<Omit<DepartmentCategory, 'id'>>
      title={record ? '编辑科室分类' : '新建科室分类'}
      open={open}
      onOpenChange={onOpenChange}
      initialValues={record}
      modalProps={{ destroyOnClose: true, forceRender: true }}
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
        name="hospital_id"
        label="所属医院"
        rules={[{ required: true, message: '请选择所属医院' }]}
        request={async () => (await listHospitals()).map((h) => ({ label: h.name, value: h.id }))}
      />
      <ProFormText name="name" label="分类名称" rules={[{ required: true, message: '请输入分类名称' }]} />
      <ProFormDigit name="sort_order" label="排序" rules={[{ required: true, message: '请输入排序' }]} />
    </ModalForm>
  );
}
