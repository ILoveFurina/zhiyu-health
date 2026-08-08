import { useEffect, useRef, useState } from 'react';
import { Button, Input, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listCampuses,
  listDepartmentCategories,
  listHospitals,
  removeDepartmentCategory,
  type Campus,
  type DepartmentCategory,
  type Hospital,
} from '@/services/organization';
import DepartmentCategoryForm from './components/DepartmentCategoryForm';

export default function DepartmentCategoryPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<DepartmentCategory | undefined>();
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [campuses, setCampuses] = useState<Campus[]>([]);
  const [all, setAll] = useState<DepartmentCategory[]>([]);
  const [keyword, setKeyword] = useState('');

  // 列表列头需把 campus_id 翻译成「医院-院区」，与表格数据并行拉一次
  useEffect(() => {
    listHospitals().then(setHospitals).catch(() => {});
    listCampuses().then(setCampuses).catch(() => {});
  }, []);

  const reload = () => actionRef.current?.reload();

  // 本地即时过滤：输入即生效，无需点查询按钮
  const filtered = keyword ? all.filter((c) => c.name.includes(keyword)) : all;

  const campusLabel = (campusId: number) => {
    const campus = campuses.find((c) => c.id === campusId);
    if (!campus) return campusId;
    const hospitalName = hospitals.find((h) => h.id === campus.hospital_id)?.name;
    return hospitalName ? `${hospitalName}-${campus.name}` : campus.name;
  };

  const columns: ProColumns<DepartmentCategory>[] = [
    { title: '序号', valueType: 'index', width: 64, align: 'center' },
    {
      title: '所属院区',
      dataIndex: 'campus_id',
      search: false,
      render: (_, row) => campusLabel(row.campus_id),
    },
    { title: '分类名称', dataIndex: 'name' },
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
        <Popconfirm key="delete" title="确认删除该科室分类？" onConfirm={async () => { await removeDepartmentCategory(row.id); reload(); }}>
          <a style={{ color: '#ff4d4f' }}>删除</a>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <PageContainer title={false}>
      <ProTable<DepartmentCategory>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={{ defaultPageSize: 10, pageSizeOptions: [10, 20, 50, 100] }}
        search={false}
        headerTitle={
          <span className="zy-searchbar">
            <Input
              placeholder="搜索分类名称"
              allowClear
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </span>
        }
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            新建科室分类
          </Button>,
        ]}
        dataSource={filtered}
        request={async () => {
          const data = await listDepartmentCategories();
          setAll(data);
          return { data, success: true };
        }}
      />
      <DepartmentCategoryForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
