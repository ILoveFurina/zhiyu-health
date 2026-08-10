import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { ModalForm, ProFormText } from '@ant-design/pro-components';
import { Button, Form, Input, InputNumber, Select, Space } from 'antd';
import type { Medication } from '@/services/prescription';
import {
  createTemplate,
  updateTemplate,
  type PrescriptionTemplate,
  type PrescriptionTemplateInput,
} from '@/services/prescriptionTemplate';

interface Props {
  open: boolean;
  record?: PrescriptionTemplate;
  medications: Medication[];
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

// record 的 items 带后端回显字段（id/medication_name/specification），提交前需裁剪成输入结构
const toInitialValues = (record: PrescriptionTemplate): PrescriptionTemplateInput => ({
  name: record.name,
  items: record.items.map(({ medication_id, dosage, frequency, duration, quantity, notes }) => ({
    medication_id, dosage, frequency, duration, quantity, notes,
  })),
});

export default function TemplateFormModal({ open, record, medications, onOpenChange, onSuccess }: Props) {
  return (
    <ModalForm<PrescriptionTemplateInput>
      key={record?.id ?? 'new'}
      title={record ? '编辑处方模板' : '新建处方模板'}
      open={open}
      onOpenChange={onOpenChange}
      width={720}
      initialValues={record ? toInitialValues(record) : { items: [{}] }}
      modalProps={{ destroyOnHidden: true, forceRender: true }}
      onFinish={async (values) => {
        if (record) {
          await updateTemplate(record.id, values);
        } else {
          await createTemplate(values);
        }
        onSuccess();
        return true;
      }}
    >
      <ProFormText name="name" label="模板名称" rules={[{ required: true, whitespace: true, message: '请输入模板名称' }]} />
      <Form.Item label="药品明细" required>
        <Form.List name="items">
          {(fields, { add, remove }) => (
            <Space direction="vertical" style={{ width: '100%' }}>
              {fields.map(({ key, name, ...rest }) => (
                <Space key={key} align="start" wrap>
                  <Form.Item {...rest} name={[name, 'medication_id']} rules={[{ required: true, message: '请选择药品' }]}>
                    <Select style={{ width: 210 }} placeholder="选择药品" options={medications.map((m) => ({
                      value: m.id, label: `${m.name}（${m.specification}）`,
                    }))} />
                  </Form.Item>
                  <Form.Item {...rest} name={[name, 'dosage']} rules={[{ required: true, whitespace: true }]}>
                    <Input placeholder="单次剂量，如 0.5g" />
                  </Form.Item>
                  <Form.Item {...rest} name={[name, 'frequency']} rules={[{ required: true, whitespace: true }]}>
                    <Input placeholder="频次，如 每日3次" />
                  </Form.Item>
                  <Form.Item {...rest} name={[name, 'duration']} rules={[{ required: true, whitespace: true }]}>
                    <Input placeholder="疗程，如 5天" />
                  </Form.Item>
                  {/* 票 88：模板明细同样由医生填写配药数量（正整数，默认 1），引用模板开方时带入处方明细 */}
                  <Form.Item {...rest} name={[name, 'quantity']} initialValue={1} rules={[{ required: true, message: '请输入配药数量' }]}>
                    <InputNumber min={1} precision={0} style={{ width: 76 }} placeholder="数量" />
                  </Form.Item>
                  <Form.Item {...rest} name={[name, 'notes']}><Input placeholder="用药备注" /></Form.Item>
                  {fields.length > 1 && <MinusCircleOutlined onClick={() => remove(name)} />}
                </Space>
              ))}
              <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>添加药品</Button>
            </Space>
          )}
        </Form.List>
      </Form.Item>
    </ModalForm>
  );
}
