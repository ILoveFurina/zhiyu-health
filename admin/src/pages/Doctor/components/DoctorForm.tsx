import { ModalForm, ProFormDatePicker, ProFormDigit, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { Form, Select, Upload, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import {
  createDoctor,
  listCampuses,
  listDepartments,
  listHospitals,
  updateDoctor,
  uploadDoctorPhoto,
  type Campus,
  type Department,
  type Doctor,
  type Hospital,
} from '@/services/organization';
import { doctorPhotoAllowedTypes, doctorPhotoMaxBytes } from '@/contracts/doctorPhoto';
import DoctorPhoto from './DoctorPhoto';

interface Props {
  open: boolean;
  record?: Doctor;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function DoctorForm({ open, record, onOpenChange, onSuccess }: Props) {
  // 主动控制回显/重置：避免 initialValues 在 open 切换时不重读导致新建残留旧数据
  const [form] = Form.useForm<Omit<Doctor, 'id'>>();
  const photoUrl = Form.useWatch('photo_url', form);

  // 院区/科室联动：院区仅作科室过滤的辅助选择（doctors 表无 campus_id，院区经科室推导，不随表单提交）
  const [campuses, setCampuses] = useState<Campus[]>([]);
  const [hospitals, setHospitals] = useState<Hospital[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [campusId, setCampusId] = useState<number | undefined>();

  useEffect(() => {
    listCampuses().then(setCampuses).catch(() => {});
    listHospitals().then(setHospitals).catch(() => {});
    listDepartments().then(setDepartments).catch(() => {});
  }, []);

  // 院区下拉拼「医院-院区」label
  const campusOptions = useMemo(
    () =>
      campuses.map((c) => {
        const hospitalName = hospitals.find((h) => h.id === c.hospital_id)?.name;
        return { label: hospitalName ? `${hospitalName}-${c.name}` : c.name, value: c.id };
      }),
    [campuses, hospitals],
  );

  // 科室下拉：按已选院区过滤，选院区后只展示该院区下的科室
  const deptOptions = useMemo(
    () =>
      departments
        .filter((d) => (campusId ? d.campus_id === campusId : true))
        .map((d) => ({ label: d.name, value: d.id })),
    [departments, campusId],
  );

  // 上传前校验：类型与大小不合法直接拦截，不发请求（上限与类型从契约推导，与 server-java 一致）
  const beforeUpload = (file: File): boolean => {
    if (!doctorPhotoAllowedTypes.includes(file.type)) {
      message.error('照片仅支持 JPEG/PNG 格式');
      return false;
    }
    if (file.size > doctorPhotoMaxBytes) {
      message.error('照片不能超过 2MB');
      return false;
    }
    return true;
  };

  // Upload 自定义请求：拿到 object_key 写入 photo_url 表单字段（不发 antd 默认请求）
  const customUpload = (options: any) => {
    const { file, onSuccess: onDone, onError } = options;
    uploadDoctorPhoto(file as File)
      .then((res) => {
        if (res.object_key) {
          form.setFieldValue('photo_url', res.object_key);
          onDone(res, file);
        } else {
          // MinIO 旁路降级：返回空 key，提示不阻塞保存
          message.warning('照片存储暂不可用，已跳过照片，档案仍可保存');
          form.setFieldValue('photo_url', undefined);
          onDone({}, file);
        }
      })
      .catch((e) => {
        message.error(e instanceof Error ? e.message : '照片上传失败');
        onError(e);
      });
    return false; // 阻止 antd 自动上传
  };

  // 移除已上传照片：清空 photo_url
  const onRemove = () => {
    form.setFieldValue('photo_url', undefined);
    return true;
  };

  return (
    <ModalForm<Omit<Doctor, 'id'>>
      form={form}
      title={record ? '编辑医生' : '新建医生'}
      open={open}
      onOpenChange={(o) => {
        if (o) {
          form.setFieldsValue(record ?? {});
          // 编辑回显：按当前科室所属院区预填院区下拉，保持联动一致
          const dept = record ? departments.find((d) => d.id === record.department_id) : undefined;
          setCampusId(dept?.campus_id);
        } else {
          form.resetFields();
          setCampusId(undefined);
        }
        onOpenChange(o);
      }}
      modalProps={{ destroyOnClose: false }}
      onFinish={async (values) => {
        if (record) {
          await updateDoctor(record.id, values);
        } else {
          await createDoctor(values);
        }
        onSuccess();
        return true;
      }}
    >
      {/* 所属院区：doctors 表无 campus_id，院区经科室推导；此处仅作科室过滤的辅助选择，不随表单提交 */}
      <Form.Item label="所属院区" required>
        <Select
          placeholder="请选择所属院区"
          options={campusOptions}
          value={campusId}
          onChange={(v: number | undefined) => {
            setCampusId(v);
            // 院区切换后，若当前科室已不属于新院区，清空以免提交错配的科室
            const currentDept = form.getFieldValue('department_id');
            if (currentDept != null) {
              const stillMatch = departments.some((d) => d.id === currentDept && d.campus_id === v);
              if (!stillMatch) form.setFieldValue('department_id', undefined);
            }
          }}
          allowClear
        />
      </Form.Item>
      <ProFormSelect
        name="department_id"
        label="所属科室"
        options={deptOptions}
        rules={[{ required: true, message: '请选择所属科室' }]}
        placeholder={campusId ? '请选择所属科室' : '请先选择所属院区'}
        disabled={!campusId}
      />
      <ProFormText name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]} />
      <ProFormSelect
        name="gender"
        label="性别"
        options={[{ label: '男', value: '男' }, { label: '女', value: '女' }]}
        rules={[{ required: true, message: '请选择性别' }]}
      />
      <ProFormDatePicker
        name="birth_date"
        label="出生日期"
        fieldProps={{ style: { width: '100%' } }}
        rules={[{ required: true, message: '请选择出生日期' }]}
      />
      <ProFormSelect
        name="title"
        label="职称"
        options={[
          { label: '主任医师', value: '主任医师' },
          { label: '副主任医师', value: '副主任医师' },
          { label: '主治医师', value: '主治医师' },
        ]}
        rules={[{ required: true, message: '请选择职称' }]}
      />
      <ProFormDigit
        name="registration_fee"
        label="挂号费(元)"
        min={0}
        fieldProps={{ precision: 2 }}
        rules={[{ required: true, message: '请输入挂号费' }]}
      />
      <ProFormText name="specialty" label="擅长" rules={[{ required: true, message: '请输入擅长' }]} />
      {/* 照片可选：上传后写入 object key；photo_url 隐藏承载实际值 */}
      <Form.Item label="照片">
        <Form.Item name="photo_url" hidden>
          <input />
        </Form.Item>
        <Upload
          listType="picture-card"
          accept="image/jpeg,image/png"
          maxCount={1}
          showUploadList={{ showPreviewIcon: false }}
          customRequest={customUpload}
          beforeUpload={beforeUpload}
          onRemove={onRemove}
          // 已有 object key 但 Upload 内部 fileList 不感知，用自定义预览覆盖显示
          fileList={[]}
        >
          {photoUrl ? (
            <div style={{ position: 'relative' }}>
              <DoctorPhoto objectKey={photoUrl} size={86} />
            </div>
          ) : (
            <div>
              <PlusOutlined />
              <div style={{ marginTop: 8 }}>上传照片</div>
            </div>
          )}
        </Upload>
        <div style={{ color: 'var(--zy-muted)', fontSize: 12 }}>支持 JPEG/PNG，不超过 2MB；照片可选</div>
      </Form.Item>
    </ModalForm>
  );
}
