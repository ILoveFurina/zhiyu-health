import { useEffect, useRef, useState } from 'react';
import { Button, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listDepartments,
  listDoctors,
  removeDoctor,
  type Department,
  type Doctor,
} from '@/services/organization';
import DoctorForm from './components/DoctorForm';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

export default function DoctorPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Doctor | undefined>();
  const [departments, setDepartments] = useState<Department[]>([]);
  const [rows, setRows] = useState<Doctor[]>([]);

  // 列表列头需把 department_id 翻译成科室名，与表格数据并行拉一次
  useEffect(() => {
    listDepartments().then(setDepartments).catch(() => {});
  }, []);

  const reload = () => actionRef.current?.reload();

  const columns: ProColumns<Doctor>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false },
    { title: '姓名', dataIndex: 'name' },
    {
      title: '所属科室',
      dataIndex: 'department_id',
      search: false,
      render: (_, row) => departments.find((d) => d.id === row.department_id)?.name ?? row.department_id,
    },
    { title: '职称', dataIndex: 'title', search: false },
    {
      title: '挂号费(元)',
      dataIndex: 'registration_fee',
      search: false,
      valueType: 'money',
    },
    { title: '擅长', dataIndex: 'specialty', search: false },
    { title: '照片', dataIndex: 'photo_url', search: false, render: (_, row) => row.photo_url || '-' },
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
        <Popconfirm key="delete" title="确认删除该医生？" onConfirm={async () => { await removeDoctor(row.id); reload(); }}>
          <Button type="link" danger>删除</Button>
        </Popconfirm>,
      ],
    },
  ];

  const stats = [
    { label: '医生总数', value: rows.length, suffix: '人' },
    { label: '所属科室', value: new Set(rows.map((r) => r.department_id)).size, suffix: '个' },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="医生管理"
        description="维护医生档案、所属科室与挂号费，为排班与接诊提供基础数据"
        tags={['职称', '挂号费', '擅长领域']}
      />
      <StatCards items={stats} />
      <ProTable<Doctor>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        search={false}
        headerTitle="医生列表"
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            + 新建医生
          </Button>,
        ]}
        request={async () => {
          const data = await listDoctors();
          setRows(data);
          return { data, success: true };
        }}
      />
      <DoctorForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
