import { useRef, useState } from 'react';
import { App, Button, Popconfirm, Select, Tag } from 'antd';
import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { deleteGraphEdge, listGraphEdges, type GraphEdgeItem } from '@/services/graphManagement';
import {
  graphEdgeTypeNames,
  graphEdgeTypes,
  graphNodeLabelNames,
  type GraphEdgeType,
} from '@/contracts/graphManagement';
import NodeSelect from './NodeSelect';
import EdgeForm from './EdgeForm';

// 关系类型配色，与 G6 可视化页 EDGE_COLORS 对齐
const EDGE_COLORS: Record<GraphEdgeType, string> = {
  INDICATES: 'orange',
  TREATED_BY: 'green',
  SUGGESTS_DEPARTMENT: 'lime',
};

/**
 * 关系页签（票 91）：类型筛选 + 按任一端节点过滤 + 分页表格。
 * 关系不可编辑（修改 = 删除后重建）；新建经 EdgeForm，
 * 两端 label 组合由 contracts 白名单约束（EdgeForm 内实现）。
 */
export default function EdgesPanel() {
  const { message } = App.useApp();
  const actionRef = useRef<ActionType>();
  const [type, setType] = useState<GraphEdgeType>();
  const [nodeId, setNodeId] = useState<string>();
  const [formOpen, setFormOpen] = useState(false);

  const reload = () => actionRef.current?.reload();

  const columns: ProColumns<GraphEdgeItem>[] = [
    {
      title: '起点节点',
      dataIndex: 'from_name',
      render: (_, row) => {
        const fromLabel = row.from_node_id.split(':')[0];
        return (
          <>
            {row.from_name}
            <Tag style={{ marginLeft: 6 }}>{graphNodeLabelNames[fromLabel as keyof typeof graphNodeLabelNames] ?? fromLabel}</Tag>
          </>
        );
      },
    },
    {
      title: '关系类型',
      dataIndex: 'type',
      width: 140,
      render: (_, row) => <Tag color={EDGE_COLORS[row.type]}>{graphEdgeTypeNames[row.type]}</Tag>,
    },
    {
      title: '终点节点',
      dataIndex: 'to_name',
      render: (_, row) => {
        const toLabel = row.to_node_id.split(':')[0];
        return (
          <>
            {row.to_name}
            <Tag style={{ marginLeft: 6 }}>{graphNodeLabelNames[toLabel as keyof typeof graphNodeLabelNames] ?? toLabel}</Tag>
          </>
        );
      },
    },
    {
      title: '操作',
      valueType: 'option',
      width: 100,
      render: (_, row) => [
        <Popconfirm
          key="delete"
          title={`确认删除「${row.from_name} → ${row.to_name}」的${graphEdgeTypeNames[row.type]}关系？`}
          onConfirm={async () => {
            await deleteGraphEdge({
              from_node_id: row.from_node_id,
              to_node_id: row.to_node_id,
              type: row.type,
            });
            message.success('关系已删除');
            reload();
          }}
        >
          <a style={{ color: '#d4605a' }}>删除</a>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<GraphEdgeItem>
        rowKey={(row) => `${row.from_node_id}|${row.type}|${row.to_node_id}`}
        actionRef={actionRef}
        columns={columns}
        search={false}
        params={{ type, nodeId }}
        pagination={{ defaultPageSize: 20, showSizeChanger: false }}
        headerTitle={
          <>
            关系列表
            <span className="zy-searchbar">
              <Select
                allowClear
                placeholder="全部关系类型"
                style={{ width: 150 }}
                value={type}
                onChange={(v) => setType(v)}
                options={graphEdgeTypes.map((t) => ({ value: t, label: graphEdgeTypeNames[t] }))}
              />
              <div style={{ width: 260 }}>
                <NodeSelect
                  value={nodeId}
                  onChange={setNodeId}
                  placeholder="按节点过滤（任一端）"
                />
              </div>
            </span>
          </>
        }
        request={async ({ current, pageSize, ...rest }) => {
          const res = await listGraphEdges({
            type: rest.type as GraphEdgeType | undefined,
            node_id: (rest.nodeId as string) || undefined,
            page: current,
            size: pageSize,
          });
          return { data: res.items, total: res.total, success: true };
        }}
        toolBarRender={() => [
          <Button key="create" type="primary" onClick={() => setFormOpen(true)}>
            新建关系
          </Button>,
        ]}
      />
      <EdgeForm
        open={formOpen}
        onOpenChange={setFormOpen}
        onSuccess={() => {
          message.success('关系已创建');
          reload();
        }}
      />
    </>
  );
}
