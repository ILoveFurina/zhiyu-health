import { useEffect, useRef, useState } from 'react';
import { Button, Input, Popconfirm } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { listDepartments, listDoctors, listHospitals, removeHospital, type Hospital } from '@/services/organization';
import HospitalForm from './components/HospitalForm';
import StatCards from '@/components/StatCards';
import LevelTag from '@/components/LevelTag';
import PageHead from '@/components/PageHead';

export default function HospitalPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Hospital | undefined>();
  const [rows, setRows] = useState<Hospital[]>([]);
  const [keyword, setKeyword] = useState('');
  const [deptTotal, setDeptTotal] = useState(0);
  const [doctorTotal, setDoctorTotal] = useState(0);

  const reload = () => actionRef.current?.reload();

  // 统计卡「科室总数 / 在职医生」需并行拉一次全量，与表格数据分看
  useEffect(() => {
    listDepartments().then((d) => setDeptTotal(d.length)).catch(() => {});
    listDoctors().then((d) => setDoctorTotal(d.length)).catch(() => {});
  }, []);

  const columns: ProColumns<Hospital>[] = [
    { title: 'ID', dataIndex: 'id', width: 64, search: false, render: (_, row) => <span className="zy-id">#{row.id}</span> },
    { title: '医院名称', dataIndex: 'name' },
    { title: '等级', dataIndex: 'level', search: false, render: (_, row) => <LevelTag level={row.level} /> },
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
          <Button type="link" danger>删除</Button>
        </Popconfirm>,
      ],
    },
  ];

  // 统计卡：从已加载行实时计算；科室/医生总数来自并行拉取的全量
  const stats = [
    { label: '医院总数', value: rows.length, suffix: '家' },
    { label: '三甲', value: rows.filter((r) => r.level === '三甲').length, suffix: '家' },
    { label: '科室总数', value: deptTotal },
    { label: '在职医生', value: doctorTotal },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="医院管理"
        description="维护机构下的医院档案与等级，为科室与医生挂靠提供基础数据。"
        tags={['三甲 / 三乙', '地理坐标', '可挂载科室']}
      />
      <StatCards items={stats} />
      <ProTable<Hospital>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        search={false}
        headerTitle="医院列表"
        toolBarRender={() => [
          <div key="searchbar" className="zy-searchbar">
            <Input
              placeholder="搜索医院名称"
              allowClear
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={() => actionRef.current?.reload()}
            />
            <Button onClick={() => actionRef.current?.reload()}>查询</Button>
          </div>,
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setOpen(true);
            }}
          >
            + 新建医院
          </Button>,
        ]}
        request={async () => {
          const data = await listHospitals();
          const filtered = keyword ? data.filter((h) => h.name.includes(keyword)) : data;
          setRows(filtered);
          return { data: filtered, success: true };
        }}
      />
      <HospitalForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}