import { ModalForm, ProFormDigit, ProFormSwitch } from '@ant-design/pro-components';
import { updateMedication, type Medication, type MedicationInput } from '@/services/medication';

interface Props {
  open: boolean;
  record?: Medication;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function MedicationForm({ open, record, onOpenChange, onSuccess }: Props) {
  return (
    <ModalForm<MedicationInput>
      title="编辑药品"
      open={open}
      onOpenChange={onOpenChange}
      initialValues={record ? { price: record.price, stock: record.stock, is_active: record.is_active } : {}}
      modalProps={{ destroyOnClose: true, forceRender: true }}
      onFinish={async (values) => {
        if (record) {
          await updateMedication(record.id, values);
        }
        onSuccess();
        return true;
      }}
    >
      <ProFormDigit
        name="price"
        label="价格(元)"
        min={0}
        fieldProps={{ precision: 2 }}
        rules={[{ required: true, message: '请输入价格' }]}
      />
      <ProFormDigit
        name="stock"
        label="库存"
        min={0}
        fieldProps={{ precision: 0 }}
        rules={[{ required: true, message: '请输入库存' }]}
      />
      <ProFormSwitch name="is_active" label="上架" />
    </ModalForm>
  );
}
