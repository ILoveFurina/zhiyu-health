import { useEffect, useRef, useState } from 'react';
import { App, Button, Popconfirm, Tag } from 'antd';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import {
  listKnowledgeDocuments,
  retryKnowledgeDocument,
  archiveKnowledgeDocument,
  type KnowledgeDocument,
} from '@/services/knowledgeDocument';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';
import UploadModal from './components/UploadModal';

export default function KnowledgeDocumentPage() {
  const { message } = App.useApp();
  const actionRef = useRef<ActionType>();
  const [open, setOpen] = useState(false);
  const [all, setAll] = useState<KnowledgeDocument[]>([]);
  const [archivingId, setArchivingId] = useState<number | undefined>();

  const reload = () => actionRef.current?.reload();

  // 5s 轮询：PROCESSING 文档需要异步等待 READY/FAILED
  useEffect(() => {
    const hasProcessing = all.some((d) => d.status === 'PROCESSING');
    if (!hasProcessing) return;
    const timer = setInterval(() => reload(), 5000);
    return () => clearInterval(timer);
  }, [all]);

  const onRetry = async (id: number) => {
    try {
      await retryKnowledgeDocument(id);
      message.success('已重新提交处理');
      reload();
    } catch {
      // errorHandler 已提示
    }
  };

  const onArchive = async (id: number) => {
    setArchivingId(id);
    try {
      await archiveKnowledgeDocument(id);
      message.success('文档已归档，关联知识块已删除');
      reload();
    } catch {
      // errorHandler 已提示
    } finally {
      setArchivingId(undefined);
    }
  };

  const statusTag = (status: KnowledgeDocument['status']) => {
    switch (status) {
      case 'PROCESSING':
        return <Tag color="processing">处理中</Tag>;
      case 'READY':
        return <Tag color="success">就绪</Tag>;
      case 'FAILED':
        return <Tag color="error">失败</Tag>;
      case 'ARCHIVED':
        return <Tag color="default">已归档</Tag>;
      default:
        return <Tag>{status}</Tag>;
    }
  };

  const sourceTag = (source: KnowledgeDocument['source']) =>
    source === 'SEED' ? <Tag color="purple">系统预置</Tag> : <Tag color="blue">上传</Tag>;

  const columns: ProColumns<KnowledgeDocument>[] = [
    { title: '序号', valueType: 'index', width: 64, align: 'center' },
    { title: '文件名', dataIndex: 'file_name', ellipsis: true },
    { title: '科室', dataIndex: 'department', search: false, render: (_, row) => row.department || '—' },
    {
      title: '来源',
      dataIndex: 'source',
      search: false,
      width: 100,
      render: (_, row) => sourceTag(row.source),
    },
    {
      title: '状态',
      dataIndex: 'status',
      search: false,
      width: 120,
      render: (_, row) => (
        <div>
          {statusTag(row.status)}
          {row.status === 'READY' && row.chunk_count > 0 && (
            <span style={{ marginLeft: 4, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
              {row.chunk_count} 段
            </span>
          )}
          {row.status === 'FAILED' && row.error_message && (
            <div style={{ fontSize: 12, color: '#ff4d4f', marginTop: 2 }}>{row.error_message}</div>
          )}
        </div>
      ),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 140,
      render: (_, row) => {
        if (row.source === 'SEED') {
          return [<span key="readonly" style={{ color: 'rgba(0,0,0,0.25)' }}>只读</span>];
        }
        const actions: React.ReactNode[] = [];
        if (row.status === 'FAILED' || row.status === 'ARCHIVED') {
          actions.push(
            <a key="retry" onClick={() => onRetry(row.id)}>
              重试
            </a>,
          );
        }
        if (row.status === 'READY' || row.status === 'FAILED') {
          actions.push(
            <Popconfirm
              key="archive"
              title="归档后关联知识块将被删除，C 端不再命中该文档知识"
              onConfirm={() => onArchive(row.id)}
              okText="确认归档"
              cancelText="取消"
            >
              <a style={{ color: '#ff4d4f' }}>归档</a>
            </Popconfirm>,
          );
        }
        return actions.length > 0 ? actions : [<span key="none" style={{ color: 'rgba(0,0,0,0.25)' }}>—</span>];
      },
    },
  ];

  const readyCount = all.filter((d) => d.status === 'READY').length;
  const processingCount = all.filter((d) => d.status === 'PROCESSING').length;
  const failedCount = all.filter((d) => d.status === 'FAILED').length;
  const totalChunks = all.reduce((s, d) => s + (d.chunk_count || 0), 0);
  const stats = [
    { label: '文档总数', value: all.length, suffix: '篇' },
    { label: '就绪', value: readyCount, suffix: '篇' },
    { label: '处理中', value: processingCount, suffix: '篇' },
    { label: '知识块总数', value: totalChunks, suffix: '段' },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="知识文档"
        description="上传 Markdown/纯文本知识文档，系统自动切分并计算向量，供 C 端导诊 RAG 检索命中"
        tags={['文档上传', '自动切分', 'RAG 知识库']}
      />
      <StatCards items={stats} />
      <ProTable<KnowledgeDocument>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        pagination={false}
        search={false}
        headerTitle="文档列表"
        dataSource={all}
        request={async () => {
          const data = await listKnowledgeDocuments();
          setAll(data);
          return { data, success: true };
        }}
        toolBarRender={() => [
          <Button key="upload" type="primary" onClick={() => setOpen(true)}>
            上传文档
          </Button>,
        ]}
      />
      <UploadModal open={open} onOpenChange={setOpen} onSuccess={reload} />
    </PageContainer>
  );
}
