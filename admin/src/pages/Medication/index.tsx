import { useRef, useState } from 'react';
import { Button, Tag } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { listMedications, type Medication } from '@/services/medication';
import MedicationForm from './components/MedicationForm';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

export default function MedicationPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Medication | undefined>();
  const [rows, setRows] = useState<Medication[]>([]);

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

  const activeCount = rows.filter((r) => r.is_active).length;
  const stats = [
    { label: '药品总数', value: rows.length, suffix: '种' },
    { label: '启用', value: activeCount, suffix: '种' },
    { label: '停用', value: rows.length - activeCount, suffix: '种' },
    { label: '总库存', value: rows.reduce((s, r) => s + (r.stock ?? 0), 0) },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="药品管理"
        description="维护药品目录、规格、价格与库存，为电子处方与药品订单提供基础数据"
        tags={['规格价格', '库存管理']}
      />
      <StatCards items={stats} />
      <ProTable<Medication>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        search={false}
        headerTitle="药品列表"
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            + 新建药品
          </Button>,
        ]}
        request={async () => {
          const data = await listMedications();
          setRows(data);
          return { data, success: true };
        }}
      />
      <MedicationForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
