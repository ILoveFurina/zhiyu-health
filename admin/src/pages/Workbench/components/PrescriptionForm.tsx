import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Select, Space } from 'antd';
import type { Medication, PrescriptionInput } from '@/services/prescription';

interface Props {
  medications: Medication[];
  submitting: boolean;
  onSubmit: (values: PrescriptionInput) => Promise<void>;
}

export default function PrescriptionForm({ medications, submitting, onSubmit }: Props) {
  return (
    <Form layout="vertical" onFinish={onSubmit} initialValues={{ items: [{}] }}>
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
                <Form.Item {...rest} name={[name, 'notes']}><Input placeholder="用药备注" /></Form.Item>
                {fields.length > 1 && <MinusCircleOutlined onClick={() => remove(name)} />}
              </Space>
            ))}
            <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>添加药品</Button>
          </Space>
        )}
      </Form.List>
      <Form.Item name="notes" label="电子处方备注" style={{ marginTop: 16 }}><Input.TextArea rows={2} /></Form.Item>
      <Button type="primary" htmlType="submit" loading={submitting}>提交审核</Button>
    </Form>
  );
}
