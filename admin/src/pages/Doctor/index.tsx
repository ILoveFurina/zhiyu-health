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

export default function DoctorPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Doctor | undefined>();
  const [departments, setDepartments] = useState<Department[]>([]);

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
          <a style={{ color: '#ff4d4f' }}>删除</a>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <PageContainer title={false}>
      <ProTable<Doctor>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        request={async () => ({ data: await listDoctors(), success: true })}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            新建医生
          </Button>,
        ]}
      />
      <DoctorForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
