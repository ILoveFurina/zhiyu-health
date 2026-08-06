import { useRef, useState } from 'react';
import { Button, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listStandardDepartments,
  removeStandardDepartment,
  type StandardDepartment,
} from '@/services/organization';
import StandardDepartmentForm from './components/StandardDepartmentForm';

export default function StandardDepartmentPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<StandardDepartment | undefined>();

  const reload = () => actionRef.current?.reload();

  const columns: ProColumns<StandardDepartment>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false },
    { title: '科类', dataIndex: 'category' },
    { title: '标准科室名称', dataIndex: 'name' },
    { title: '排序', dataIndex: 'sort_order', search: false, width: 80 },
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
        <Popconfirm key="delete" title="确认删除该标准科室？" onConfirm={async () => { await removeStandardDepartment(row.id); reload(); }}>
          <a style={{ color: '#ff4d4f' }}>删除</a>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <PageContainer title={false}>
      <ProTable<StandardDepartment>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        request={async () => ({ data: await listStandardDepartments(), success: true })}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            新建标准科室
          </Button>,
        ]}
      />
      <StandardDepartmentForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
