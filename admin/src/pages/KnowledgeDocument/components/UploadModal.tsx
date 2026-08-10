import { useState } from 'react';
import { App, Upload } from 'antd';
import { ModalForm, ProFormSelect } from '@ant-design/pro-components';
import { InboxOutlined } from '@ant-design/icons';
import { UploadFile } from 'antd';
import { listStandardDepartments, type StandardDepartment } from '@/services/organization';
import { uploadKnowledgeDocument } from '@/services/knowledgeDocument';
import { uploadAllowedTypes, uploadMaxFileBytes, uploadAllowedExtensions } from '@/contracts/knowledgeDocument';

const { Dragger } = Upload;

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
}

export default function UploadModal({ open, onOpenChange, onSuccess }: Props) {
  const { message } = App.useApp();
  const [departments, setDepartments] = useState<StandardDepartment[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);

  const loadDepartments = async () => {
    if (departments.length === 0) {
      const data = await listStandardDepartments();
      setDepartments(data);
    }
  };

  const beforeUpload = (file: File): boolean => {
    if (!uploadAllowedTypes.includes(file.type)) {
      message.error('仅支持纯文本和 Markdown 格式');
      return false;
    }
    if (file.size > uploadMaxFileBytes) {
      message.error('文件不能超过 2MB');
      return false;
    }
    setSelectedFile(file);
    return false; // 阻止 antd 自动上传
  };

  const onRemove = () => {
    setSelectedFile(null);
  };

  const handleSubmit = async (values: { department: number }) => {
    if (!selectedFile) {
      message.error('请选择文档文件');
      return false;
    }
    const matched = departments.find((d) => d.id === values.department);
    if (!matched) {
      message.error('请选择标准科室');
      return false;
    }
    setUploading(true);
    try {
      await uploadKnowledgeDocument(selectedFile, matched.name);
      message.success('文档已上传，正在处理中');
      setSelectedFile(null);
      onOpenChange(false);
      onSuccess();
      return true;
    } catch (e) {
      message.error(e instanceof Error ? e.message : '文档上传失败');
      return false;
    } finally {
      setUploading(false);
    }
  };

  const fileList: UploadFile[] = selectedFile
    ? [
        {
          uid: '-1',
          name: selectedFile.name,
          status: 'done',
          size: selectedFile.size,
          type: selectedFile.type,
        } as UploadFile,
      ]
    : [];

  const deptOptions = departments.map((d) => ({
    label: `${d.category}-${d.name}`,
    value: d.id,
  }));

  return (
    <ModalForm
      title="上传知识文档"
      open={open}
      onOpenChange={(v) => {
        onOpenChange(v);
        if (v) loadDepartments();
        if (!v) setSelectedFile(null);
      }}
      onFinish={handleSubmit}
      modalProps={{ destroyOnClose: true, maskClosable: false }}
      width={560}
    >
      <ProFormSelect
        name="department"
        label="标准科室"
        options={deptOptions}
        rules={[{ required: true, message: '请选择标准科室' }]}
        placeholder="文档级科室，切分后所有 chunk 继承"
        fieldProps={{ showSearch: true, filterOption: (input, option) =>
          (option?.label as string)?.toLowerCase().includes(input.toLowerCase()) }}
      />
      <div style={{ marginBottom: 8 }}>
        <label style={{ fontSize: 14, color: 'rgba(0,0,0,0.88)' }}>文档文件</label>
        <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', marginTop: 2 }}>
          支持 {uploadAllowedExtensions.join(' / ')} 格式，单文件不超过 2MB
        </div>
      </div>
      <Dragger
        accept={uploadAllowedExtensions.join(',')}
        maxCount={1}
        fileList={fileList}
        beforeUpload={beforeUpload}
        onRemove={onRemove}
        disabled={uploading}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
      </Dragger>
    </ModalForm>
  );
}
