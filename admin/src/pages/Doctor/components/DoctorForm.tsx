import { ModalForm, ProFormDatePicker, ProFormDigit, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { Form, Upload, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { createDoctor, listDepartments, updateDoctor, uploadDoctorPhoto, type Doctor } from '@/services/organization';
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
        } else {
          form.resetFields();
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
      <ProFormSelect
        name="department_id"
        label="所属科室"
        rules={[{ required: true, message: '请选择所属科室' }]}
        request={async () => (await listDepartments()).map((d) => ({ label: d.name, value: d.id }))}
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
      <ProFormText name="title" label="职称" rules={[{ required: true, message: '请输入职称' }]} />
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
