import { useEffect, useRef, useState } from 'react';
import { Button, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listCampuses,
  listDepartmentCategories,
  listDepartments,
  listHospitals,
  listStandardDepartments,
  removeDepartment,
  type Campus,
  type Department,
  type DepartmentCategory,
  type Hospital,
  type StandardDepartment,
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
  const [campuses, setCampuses] = useState<Campus[]>([]);
  const [categories, setCategories] = useState<DepartmentCategory[]>([]);
  const [standardDepartments, setStandardDepartments] = useState<StandardDepartment[]>([]);

  // 列表列头需把 campus_id/category_id/standard_department_id 翻译成名称，与表格数据并行拉一次
  useEffect(() => {
    listHospitals().then(setHospitals).catch(() => {});
    listCampuses().then(setCampuses).catch(() => {});
    listDepartmentCategories().then(setCategories).catch(() => {});
    listStandardDepartments().then(setStandardDepartments).catch(() => {});
  }, []);

  const reload = () => actionRef.current?.reload();

  const campusLabel = (campusId: number) => {
    const campus = campuses.find((c) => c.id === campusId);
    if (!campus) return campusId;
    const hospitalName = hospitals.find((h) => h.id === campus.hospital_id)?.name;
    return hospitalName ? `${hospitalName}-${campus.name}` : campus.name;
  };

  const columns: ProColumns<Department>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false },
    { title: '科室名称', dataIndex: 'name' },
    {
      title: '所属院区',
      dataIndex: 'campus_id',
      search: false,
      render: (_, row) => campusLabel(row.campus_id),
    },
    {
      title: '科室分类',
      dataIndex: 'category_id',
      search: false,
      render: (_, row) => categories.find((c) => c.id === row.category_id)?.name ?? row.category_id,
    },
    {
      title: '标准科室',
      dataIndex: 'standard_department_id',
      search: false,
      render: (_, row) => {
        const sd = standardDepartments.find((s) => s.id === row.standard_department_id);
        return sd ? `${sd.category}-${sd.name}` : row.standard_department_id;
      },
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
    { label: '挂靠院区', value: new Set(rows.map((r) => r.campus_id)).size, suffix: '个' },
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
