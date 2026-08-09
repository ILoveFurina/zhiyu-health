import { ModalForm, ProFormDigit, ProFormSwitch } from '@ant-design/pro-components';
import { Form } from 'antd';
import { updateMedication, type Medication, type MedicationInput } from '@/services/medication';

interface Props {
  open: boolean;
  record?: Medication;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function MedicationForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<MedicationInput>();

  return (
    <ModalForm<MedicationInput>
      form={form}
      title={record ? '编辑药品' : '新建药品'}
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
      <ProFormSwitch name="is_active" label="启用" />
      <ProFormSwitch
        name="is_prescription"
        label="处方药"
        tooltip="开启表示该药品为处方药，C 端须凭已审核电子处方购买；关闭则为非处方药(OTC)，可直接下单"
        rules={[{ required: true, message: '请选择是否处方药' }]}
      />
    </ModalForm>
  );
}
