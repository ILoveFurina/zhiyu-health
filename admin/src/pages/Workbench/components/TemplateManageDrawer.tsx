import { useCallback, useEffect, useState } from 'react';
import { App, Button, Drawer, Popconfirm, Space, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { fetchMedications, type Medication } from '@/services/prescription';
import { deleteTemplate, listTemplates, type PrescriptionTemplate } from '@/services/prescriptionTemplate';
import TemplateFormModal from './TemplateFormModal';

interface Props {
  open: boolean;
  onClose: () => void;
}

export default function TemplateManageDrawer({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [templates, setTemplates] = useState<PrescriptionTemplate[]>([]);
  const [medications, setMedications] = useState<Medication[]>([]);
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [record, setRecord] = useState<PrescriptionTemplate | undefined>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [templateList, medicationOptions] = await Promise.all([listTemplates(), fetchMedications()]);
      setTemplates(templateList);
      setMedications(medicationOptions);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { if (open) load().catch(() => {}); }, [open, load]);

  const columns: ColumnsType<PrescriptionTemplate> = [
    { title: '模板名称', dataIndex: 'name' },
    {
      title: '药品明细',
      dataIndex: 'items',
      render: (items: PrescriptionTemplate['items']) => (
        <Typography.Text type="secondary">
          {items.map((item) => item.medication_name).join('、') || '—'}
        </Typography.Text>
      ),
    },
    { title: '创建时间', dataIndex: 'created_at', width: 180 },
    {
      title: '操作',
      width: 120,
      render: (_, row) => (
        <Space>
          <a onClick={() => { setRecord(row); setFormOpen(true); }}>编辑</a>
          <Popconfirm
            title="确认删除该模板？"
            onConfirm={async () => {
              await deleteTemplate(row.id);
              message.success('模板已删除');
              await load();
            }}
          >
            <a style={{ color: '#ff4d4f' }}>删除</a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Drawer title="处方模板管理" width={720} open={open} onClose={onClose} destroyOnHidden>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Button type="primary" onClick={() => { setRecord(undefined); setFormOpen(true); }}>新建模板</Button>
        <Table<PrescriptionTemplate> rowKey="id" loading={loading} columns={columns}
          dataSource={templates} pagination={false} />
      </Space>
      <TemplateFormModal open={formOpen} record={record} medications={medications}
        onOpenChange={setFormOpen} onSuccess={load} />
    </Drawer>
  );
}
