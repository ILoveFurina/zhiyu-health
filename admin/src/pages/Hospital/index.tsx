import { useRef, useState } from 'react';
import { Button, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { listHospitals, removeHospital, type Hospital } from '@/services/organization';
import HospitalForm from './components/HospitalForm';

export default function HospitalPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Hospital | undefined>();

  const reload = () => actionRef.current?.reload();

  const columns: ProColumns<Hospital>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false },
    { title: '医院名称', dataIndex: 'name' },
    { title: '等级', dataIndex: 'level' },
    {
      title: '操作',
      valueType: 'option',
      width: 140,
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
        <Popconfirm key="delete" title="确认删除该医院？" onConfirm={async () => { await removeHospital(row.id); reload(); }}>
          <a style={{ color: '#ff4d4f' }}>删除</a>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <PageContainer title={false}>
      <ProTable<Hospital>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        request={async () => ({ data: await listHospitals(), success: true })}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            新建医院
          </Button>,
        ]}
      />
      <HospitalForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
