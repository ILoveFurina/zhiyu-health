import { ImportOutlined, MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, App, Button, Form, Input, List, Modal, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  checkPrescriptionSafety,
  type Medication,
  type PrescriptionInput,
  type SafetyCheckResult,
} from '@/services/prescription';
import { listTemplates, type PrescriptionTemplate } from '@/services/prescriptionTemplate';

interface Props {
  appointmentId: number;
  medications: Medication[];
  submitting: boolean;
  onSubmit: (values: PrescriptionInput) => Promise<void>;
}

export default function PrescriptionForm({ appointmentId, medications, submitting, onSubmit }: Props) {
  const { message } = App.useApp();
  const [form] = Form.useForm<PrescriptionInput>();
  const items = Form.useWatch('items', form) ?? [];
  const idsKey = Array.from(new Set(
    items.map((item) => item?.medication_id).filter((id): id is number => typeof id === 'number'),
  )).join(',');
  const [safety, setSafety] = useState<SafetyCheckResult>();
  const [checking, setChecking] = useState(false);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [templates, setTemplates] = useState<PrescriptionTemplate[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);

  const openTemplateModal = () => {
    setTemplateOpen(true);
    setTemplatesLoading(true);
    listTemplates()
      .then(setTemplates)
      .catch(() => message.error('模板列表加载失败'))
      .finally(() => setTemplatesLoading(false));
  };

  // 模板药品可能已停用而不在当前可选列表，直接跳过并告知医生，导入明细仍可继续编辑
  const applyTemplate = (template: PrescriptionTemplate) => {
    const available = new Set(medications.map((m) => m.id));
    const skipped: string[] = [];
    const nextItems: PrescriptionInput['items'] = [];
    for (const item of template.items) {
      if (available.has(item.medication_id)) {
        nextItems.push({
          medication_id: item.medication_id,
          dosage: item.dosage,
          frequency: item.frequency,
          duration: item.duration,
          notes: item.notes,
        });
      } else {
        skipped.push(item.medication_name);
      }
    }
    form.setFieldsValue({ items: nextItems.length > 0 ? nextItems : [{}] });
    if (skipped.length > 0) {
      message.warning(`模板中药品 ${skipped.join('、')} 已停用或不可选，已跳过`);
    }
    setTemplateOpen(false);
  };

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
            <Space>
              <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>添加药品</Button>
              <Button icon={<ImportOutlined />} onClick={openTemplateModal}>从模板导入</Button>
            </Space>
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
      <Modal title="从处方模板导入" open={templateOpen} onCancel={() => setTemplateOpen(false)} footer={null} destroyOnHidden>
        <List
          loading={templatesLoading}
          dataSource={templates}
          locale={{ emptyText: '暂无处方模板，可先在医生接诊台“处方模板”中创建' }}
          renderItem={(template) => (
            <List.Item actions={[<a key="import" onClick={() => applyTemplate(template)}>导入</a>]}>
              <List.Item.Meta
                title={template.name}
                description={template.items.map((item) => item.medication_name).join('、') || '无药品明细'}
              />
            </List.Item>
          )}
        />
      </Modal>
    </Form>
  );
}
