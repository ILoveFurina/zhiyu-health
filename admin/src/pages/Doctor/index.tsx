import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, Popconfirm, Select } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listCampuses,
  listDepartments,
  listDoctors,
  listHospitals,
  removeDoctor,
  type Campus,
  type Department,
  type Doctor,
  type Hospital,
} from '@/services/organization';
import DoctorForm from './components/DoctorForm';
import DoctorPhoto from './components/DoctorPhoto';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

// 由出生日期计算年龄：已过生日取整岁，未过则减一
function ageOf(birthDate?: string): number | undefined {
  if (!birthDate) return undefined;
  const birth = new Date(birthDate);
  if (Number.isNaN(birth.getTime())) return undefined;
  const now = new Date();
  let age = now.getFullYear() - birth.getFullYear();
  const m = now.getMonth() - birth.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < birth.getDate())) age -= 1;
  return age;
}

export default function DoctorPage() {
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<Doctor | undefined>();
  const [departments, setDepartments] = useState<Department[]>([]);
  const [campuses, setCampuses] = useState<Campus[]>([]);
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [all, setAll] = useState<Doctor[]>([]);
  const [nameKw, setNameKw] = useState('');
  const [campusId, setCampusId] = useState<number | undefined>();
  const [deptId, setDeptId] = useState<number | undefined>();
  const [titleKw, setTitleKw] = useState<string | undefined>();
  const [genderKw, setGenderKw] = useState<string | undefined>();

  // 列表列头需把 department_id 翻译成科室名、院区名，与表格数据并行拉一次
  useEffect(() => {
    listDepartments().then(setDepartments).catch(() => {});
    listCampuses().then(setCampuses).catch(() => {});
    listHospitals().then(setHospitals).catch(() => {});
  }, []);

  const reload = () => actionRef.current?.reload();

  // 院区下拉：拼「医院-院区」label
  const campusOptions = useMemo(
    () =>
      campuses.map((c) => {
        const hospitalName = hospitals.find((h) => h.id === c.hospital_id)?.name;
        return { label: hospitalName ? `${hospitalName}-${c.name}` : c.name, value: c.id };
      }),
    [campuses, hospitals],
  );

  // 按已选院区过滤科室下拉，避免全量科室混杂
  const deptOptions = useMemo(
    () =>
      departments
        .filter((d) => (campusId ? d.campus_id === campusId : true))
        .map((d) => ({ label: d.name, value: d.id })),
    [departments, campusId],
  );

  // 职称下拉：从已有医生数据去重生成
  const titleOptions = useMemo(
    () => Array.from(new Set(all.map((d) => d.title).filter(Boolean))).map((t) => ({ label: t, value: t })),
    [all],
  );

  // 本地即时过滤：姓名 + 所属院区 + 所属科室 + 职称 + 性别，输入即生效
  const filtered = all.filter((d) => {
    const okName = nameKw ? d.name.includes(nameKw) : true;
    const dept = departments.find((x) => x.id === d.department_id);
    const okCampus = campusId != null ? dept?.campus_id === campusId : true;
    const okDept = deptId != null ? d.department_id === deptId : true;
    const okTitle = titleKw ? d.title === titleKw : true;
    const okGender = genderKw ? d.gender === genderKw : true;
    return okName && okCampus && okDept && okTitle && okGender;
  });

  const campusLabel = (departmentId: number) => {
    const dept = departments.find((d) => d.id === departmentId);
    if (!dept) return departmentId;
    const campus = campuses.find((c) => c.id === dept.campus_id);
    if (!campus) return dept.campus_id;
    const hospitalName = hospitals.find((h) => h.id === campus.hospital_id)?.name;
    return hospitalName ? `${hospitalName}-${campus.name}` : campus.name;
  };

  const columns: ProColumns<Doctor>[] = [
    { title: '序号', valueType: 'index', width: 64, align: 'center' },
    { title: '姓名', dataIndex: 'name', width: 90, ellipsis: true },
    {
      title: '所属院区',
      dataIndex: 'campusId',
      search: false,
      width: 180,
      ellipsis: true,
      render: (_, row) => campusLabel(row.department_id),
    },
    {
      title: '所属科室',
      dataIndex: 'department_id',
      search: false,
      width: 110,
      ellipsis: true,
      render: (_, row) => departments.find((d) => d.id === row.department_id)?.name ?? row.department_id,
    },
    { title: '性别', dataIndex: 'gender', search: false, width: 60, align: 'center' },
    {
      title: '年龄',
      dataIndex: 'birth_date',
      search: false,
      width: 60,
      align: 'center',
      render: (_, row) => {
        const age = ageOf(row.birth_date);
        return age != null ? age : '-';
      },
    },
    { title: '职称', dataIndex: 'title', search: false, width: 100, ellipsis: true },
    {
      title: '挂号费(元)',
      dataIndex: 'registration_fee',
      search: false,
      width: 96,
      align: 'right',
      valueType: 'money',
    },
    { title: '擅长', dataIndex: 'specialty', search: false, width: 160, ellipsis: true },
    {
      title: '照片',
      dataIndex: 'photo_url',
      search: false,
      width: 72,
      align: 'center',
      render: (_, row) => <DoctorPhoto objectKey={row.photo_url} size={40} />,
    },
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
    { label: '医生总数', value: filtered.length, suffix: '人' },
    { label: '所属科室', value: new Set(filtered.map((r) => r.department_id)).size, suffix: '个' },
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
        scroll={{ x: 1080 }}
        className="zy-doctor-table"
        headerTitle={
          <>
            医生列表
            <span className="zy-searchbar zy-searchbar-compact">
              <Input
                placeholder="搜索姓名"
                allowClear
                value={nameKw}
                onChange={(e) => setNameKw(e.target.value)}
              />
              <Select
                className="zy-sel-wide"
                placeholder="所属院区"
                allowClear
                popupMatchSelectWidth={false}
                value={campusId}
                onChange={(v) => { setCampusId(v); setDeptId(undefined); }}
                options={campusOptions}
              />
              <Select
                placeholder="所属科室"
                allowClear
                value={deptId}
                onChange={(v) => setDeptId(v)}
                options={deptOptions}
              />
              <Select
                placeholder="职称"
                allowClear
                value={titleKw}
                onChange={(v) => setTitleKw(v)}
                options={titleOptions}
              />
              <Select
                className="zy-sel-narrow"
                placeholder="性别"
                allowClear
                value={genderKw}
                onChange={(v) => setGenderKw(v)}
                options={[{ label: '男', value: '男' }, { label: '女', value: '女' }]}
              />
            </span>
          </>
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
            + 新建医生
          </Button>,
        ]}
        dataSource={filtered}
        request={async () => {
          const data = await listDoctors();
          setAll(data);
          return { data, success: true };
        }}
      />
      <DoctorForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
