import { useRef, useState } from 'react';
import { App, Button, Input, Popconfirm, Select, Tag } from 'antd';
import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { deleteGraphNode, listGraphNodes, type GraphNodeItem } from '@/services/graphManagement';
import {
  graphNodeLabelNames,
  graphNodeLabels,
  type GraphNodeLabel,
} from '@/contracts/graphManagement';
import NodeForm from './NodeForm';

interface Props {
  /** G6 可视化页「编辑」跳转携带的名称关键词，进页即按此过滤定位节点 */
  initialKeyword?: string;
}

/**
 * 节点页签（票 89）：label 筛选 + 名称模糊搜索 + 分页表格，
 * 新建/编辑经 NodeForm，删除经 Popconfirm 确认。
 * 改/删命中同名 RAG 知识块时（rag_chunk_count 非 null）弹同步维护提示；
 * 删除保护 409 时把 detail 中的关系计数展示给用户，引导先删关系。
 */
export default function NodesPanel({ initialKeyword }: Props) {
  const { message, modal } = App.useApp();
  const actionRef = useRef<ActionType>();
  const [label, setLabel] = useState<GraphNodeLabel>();
  const [keyword, setKeyword] = useState(initialKeyword ?? '');
  const [formOpen, setFormOpen] = useState(false);
  const [record, setRecord] = useState<GraphNodeItem | undefined>();

  const reload = () => actionRef.current?.reload();

  // pgvector 对齐护栏（票 89 决策 3）：仅提示不阻断，真正对齐由后续 RAG 管理票解决
  const warnRagChunks = (count: number | null, nodeName: string) => {
    if (count != null && count > 0) {
      modal.warning({
        title: 'RAG 知识块对齐提醒',
        content: `该症状关联 ${count} 条 RAG 知识块（节点「${nodeName}」），建议同步维护。知识图谱与知识库不联动更新。`,
      });
    }
  };

  const onDelete = async (row: GraphNodeItem) => {
    try {
      const res = await deleteGraphNode(row.node_id);
      message.success(`节点「${row.name}」已删除`);
      warnRagChunks(res.rag_chunk_count, row.name);
      reload();
    } catch (err: any) {
      // 删除保护（票 89 决策 5）：detail 含关系计数，提示先在「关系」页签处理
      if (err?.response?.status === 409) {
        const detail = err?.response?.data?.detail;
        modal.warning({
          title: '无法删除节点',
          content: `${typeof detail === 'string' ? detail : `节点「${row.name}」仍关联关系`}。请先在「关系」页签删除该节点的全部关系。`,
        });
        return;
      }
      // 其余错误（404 等）走统一提示
      const detail = err?.response?.data?.detail;
      message.error(typeof detail === 'string' ? detail : '删除失败，请稍后重试');
    }
  };

  const columns: ProColumns<GraphNodeItem>[] = [
    { title: '名称', dataIndex: 'name' },
    {
      title: '类型',
      dataIndex: 'label',
      width: 100,
      render: (_, row) => <Tag color="geekblue">{graphNodeLabelNames[row.label]}</Tag>,
    },
    {
      title: '别名',
      dataIndex: 'aliases',
      render: (_, row) =>
        row.aliases && row.aliases.length > 0
          ? row.aliases.map((a) => <Tag key={a}>{a}</Tag>)
          : '—',
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (_, row) => row.description || '—',
    },
    {
      title: '操作',
      valueType: 'option',
      width: 120,
      render: (_, row) => [
        <a
          key="edit"
          onClick={() => {
            setRecord(row);
            setFormOpen(true);
          }}
        >
          编辑
        </a>,
        <Popconfirm
          key="delete"
          title={`确认删除节点「${row.name}」？`}
          description="删除保护：仍带关系的节点将被拒绝"
          onConfirm={() => onDelete(row)}
        >
          <a style={{ color: '#d4605a' }}>删除</a>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<GraphNodeItem>
        rowKey="node_id"
        actionRef={actionRef}
        columns={columns}
        search={false}
        params={{ label, keyword }}
        pagination={{ defaultPageSize: 20, showSizeChanger: false }}
        headerTitle={
          <>
            节点列表
            <span className="zy-searchbar">
              <Select
                allowClear
                placeholder="全部类型"
                style={{ width: 130 }}
                value={label}
                onChange={(v) => setLabel(v)}
                options={graphNodeLabels.map((l) => ({ value: l, label: graphNodeLabelNames[l] }))}
              />
              <Input.Search
                placeholder="搜索节点名称"
                allowClear
                defaultValue={initialKeyword}
                onSearch={(v) => setKeyword(v.trim())}
                style={{ width: 220 }}
              />
            </span>
          </>
        }
        request={async ({ current, pageSize, ...rest }) => {
          const res = await listGraphNodes({
            label: rest.label as GraphNodeLabel | undefined,
            keyword: (rest.keyword as string) || undefined,
            page: current,
            size: pageSize,
          });
          return { data: res.items, total: res.total, success: true };
        }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setRecord(undefined);
              setFormOpen(true);
            }}
          >
            新建节点
          </Button>,
        ]}
      />
      <NodeForm
        open={formOpen}
        record={record}
        onOpenChange={setFormOpen}
        onSuccess={(count, name) => {
          message.success(record ? `节点「${name}」已更新` : `节点「${name}」已创建`);
          warnRagChunks(count, name);
          reload();
        }}
      />
    </>
  );
}
