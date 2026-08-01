import { useRef, useState } from 'react';
import { Tag } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { listMedications, type Medication } from '@/services/medication';
import MedicationForm from './components/MedicationForm';

export default function MedicationPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Medication | undefined>();

  const reload = () => actionRef.current?.reload();

  const columns: ProColumns<Medication>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false },
    { title: '药品名称', dataIndex: 'name' },
    { title: '通用名', dataIndex: 'generic_name', search: false },
    { title: '规格', dataIndex: 'specification', search: false },
    { title: '价格(元)', dataIndex: 'price', search: false, valueType: 'digit', fieldProps: { precision: 2 } },
    { title: '库存', dataIndex: 'stock', search: false },
    {
      title: '状态',
      dataIndex: 'is_active',
      search: false,
      render: (_, row) =>
        row.is_active ? <Tag color="green">启用</Tag> : <Tag color="default">停用</Tag>,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 100,
      render: (_, row) => [
        <a
          key="edit"
          onClick={() => {
            setRecord(row);
            setOpen(true);
          }}
        >
          编辑
        </a>,
      ],
    },
  ];

  return (
    <PageContainer title={false}>
      <ProTable<Medication>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        request={async () => ({ data: await listMedications(), success: true })}
      />
      <MedicationForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
