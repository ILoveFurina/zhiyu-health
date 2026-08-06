import { useEffect, useRef, useState } from 'react';
import { Button, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listDepartments,
  listHospitals,
  removeDepartment,
  type Department,
  type Hospital,
} from '@/services/organization';
import DepartmentForm from './components/DepartmentForm';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

export default function DepartmentPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Department | undefined>();
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [rows, setRows] = useState<Department[]>([]);

  // 列表列头需把 hospital_id 翻译成医院名，与表格数据并行拉一次
  useEffect(() => {
    listHospitals().then(setHospitals).catch(() => {});
  }, []);

  const reload = () => actionRef.current?.reload();

  const columns: ProColumns<Department>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false },
    { title: '科室名称', dataIndex: 'name' },
    {
      title: '所属医院',
      dataIndex: 'hospital_id',
      search: false,
      render: (_, row) => hospitals.find((h) => h.id === row.hospital_id)?.name ?? row.hospital_id,
    },
    { title: '楼层', dataIndex: 'floor', search: false },
    { title: '位置', dataIndex: 'location', search: false },
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
        <Popconfirm key="delete" title="确认删除该科室？" onConfirm={async () => { await removeDepartment(row.id); reload(); }}>
          <Button type="link" danger>删除</Button>
        </Popconfirm>,
      ],
    },
  ];

  const stats = [
    { label: '科室总数', value: rows.length },
    { label: '挂靠医院', value: new Set(rows.map((r) => r.hospital_id)).size, suffix: '家' },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="科室管理"
        description="维护医院下的科室信息与位置，为医生排班与挂号提供基础数据"
        tags={['所属医院', '楼层位置']}
      />
      <StatCards items={stats} />
      <ProTable<Department>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        search={false}
        headerTitle="科室列表"
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            + 新建科室
          </Button>,
        ]}
        request={async () => {
          const data = await listDepartments();
          setRows(data);
          return { data, success: true };
        }}
      />
      <DepartmentForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
