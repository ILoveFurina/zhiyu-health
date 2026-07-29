import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  checkPrescriptionSafety,
  type Medication,
  type PrescriptionInput,
  type SafetyCheckResult,
} from '@/services/prescription';

interface Props {
  appointmentId: number;
  medications: Medication[];
  submitting: boolean;
  onSubmit: (values: PrescriptionInput) => Promise<void>;
}

export default function PrescriptionForm({ appointmentId, medications, submitting, onSubmit }: Props) {
  const [form] = Form.useForm<PrescriptionInput>();
  const items = Form.useWatch('items', form) ?? [];
  const idsKey = Array.from(new Set(
    items.map((item) => item?.medication_id).filter((id): id is number => typeof id === 'number'),
  )).join(',');
  const [safety, setSafety] = useState<SafetyCheckResult>();
  const [checking, setChecking] = useState(false);

  // 选药变化后防抖调用 server-java 确定性禁忌检查；提交侧仍会复跑同一规则。
  useEffect(() => {
    if (!idsKey) {
      setSafety(undefined);
      setChecking(false);
      return;
    }
    const medicationIds = idsKey.split(',').map(Number);
    let stale = false;
    setChecking(true);
    const timer = setTimeout(() => {
      checkPrescriptionSafety(appointmentId, medicationIds)
        .then((result) => { if (!stale) setSafety(result); })
        .catch(() => { if (!stale) setSafety(undefined); })
        .finally(() => { if (!stale) setChecking(false); });
    }, 300);
    return () => { stale = true; clearTimeout(timer); };
  }, [appointmentId, idsKey]);

  const blocked = safety?.blocked === true;

  return (
    <Form form={form} layout="vertical" onFinish={onSubmit} initialValues={{ items: [{}] }}>
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
      {safety && (
        <Alert
          style={{ marginBottom: 16 }}
          type={blocked ? 'error' : 'success'}
          showIcon
          message={safety.message}
          description={blocked ? (
            <Space direction="vertical" size={4}>
              {safety.reasons.map((reason) => <Typography.Text key={reason} type="danger">{reason}</Typography.Text>)}
              {safety.advice && <Typography.Text strong type="danger">{safety.advice}</Typography.Text>}
            </Space>
          ) : undefined}
        />
      )}
      <Button type="primary" htmlType="submit" loading={submitting} disabled={blocked || checking}>提交审核</Button>
    </Form>
  );
}
