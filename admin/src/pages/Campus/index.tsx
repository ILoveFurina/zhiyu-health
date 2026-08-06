import { useEffect, useRef, useState } from 'react';
import { Button, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listCampuses,
  listHospitals,
  removeCampus,
  type Campus,
  type Hospital,
} from '@/services/organization';
import CampusForm from './components/CampusForm';

export default function CampusPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Campus | undefined>();
  const [hospitals, setHospitals] = useState<Hospital[]>([]);

  // 列表列头需把 hospital_id 翻译成医院名，与表格数据并行拉一次
  useEffect(() => {
    listHospitals().then(setHospitals).catch(() => {});
  }, []);

  const reload = () => actionRef.current?.reload();

  const columns: ProColumns<Campus>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false },
    {
      title: '所属医院',
      dataIndex: 'hospital_id',
      search: false,
      render: (_, row) => hospitals.find((h) => h.id === row.hospital_id)?.name ?? row.hospital_id,
    },
    { title: '院区名称', dataIndex: 'name' },
    { title: '城市', dataIndex: 'city_name', search: false },
    { title: '城市代码', dataIndex: 'city_code', search: false },
    { title: '地址', dataIndex: 'address', search: false },
    { title: '楼层', dataIndex: 'floor', search: false },
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
        <Popconfirm key="delete" title="确认删除该院区？" onConfirm={async () => { await removeCampus(row.id); reload(); }}>
          <a style={{ color: '#ff4d4f' }}>删除</a>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <PageContainer title={false}>
      <ProTable<Campus>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        request={async () => ({ data: await listCampuses(), success: true })}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            新建院区
          </Button>,
        ]}
      />
      <CampusForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
