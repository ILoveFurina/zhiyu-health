import { useEffect, useRef, useState } from 'react';
import { Drawer, Spin, Tag, Empty, message } from 'antd';
import { PageContainer } from '@ant-design/pro-components';
import { Graph } from '@antv/g6';
import {
  fetchGraphProjection,
  fetchGraphNodeDetail,
  type GraphNode,
  type GraphNodeDetail,
} from '@/services/knowledgeGraph';

// 五类节点颜色（按 group 着色，grilling 决策 6）
const GROUP_COLORS: Record<string, string> = {
  Symptom: '#ff7875',
  Disease: '#ffa940',
  Department: '#52c41a',
  Medication: '#36cfc9',
  Contraindication: '#722ed1',
};

// 节点类型中文标签
const GROUP_LABELS: Record<string, string> = {
  Symptom: '症状',
  Disease: '疾病',
  Department: '科室',
  Medication: '药品',
  Contraindication: '禁忌',
};

// 边类型中文标签
const EDGE_LABELS: Record<string, string> = {
  INDICATES: '关联疾病',
  TREATED_BY: '归属科室',
  SUGGESTS_DEPARTMENT: '建议科室',
  TREATS: '治疗',
  CONTRAINDICATED_FOR: '禁忌',
  INTERACTS_WITH: '相互作用',
};

/**
 * 医学知识图谱可视化页（票 13）。
 *
 * 仿 Obsidian 风格：力导向布局 + 聚类色团 + 节点大小按连接数自适应 +
 * hover 高亮邻居暗化其他。数据经 server-java 鉴权后转调 server-py 只读接口（ADR-0013）。
 */
export default function KnowledgeGraphPage() {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<GraphNodeDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);

  /** 点击节点取详情（grilling 决策 6：属性不塞进投影，点击时另取）。 */
  async function handleNodeClick(nodeId: string) {
    setDrawerOpen(true);
    setDetailLoading(true);
    try {
      const data = await fetchGraphNodeDetail(nodeId);
      setDetail(data);
    } catch {
      message.error('节点详情加载失败');
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    let resizeTimer: ReturnType<typeof setTimeout> | null = null;

    async function loadAndRender() {
      try {
        const projection = await fetchGraphProjection();
        if (cancelled) return;

        if (!projection.nodes || projection.nodes.length === 0) {
          setLoading(false);
          return;
        }

        // G6 v5 数据格式：节点带 data 字段，边带 source/target/data
        const nodes = projection.nodes.map((n: GraphNode) => ({
          id: n.id,
          data: {
            label: n.label,
            group: n.group,
            color: GROUP_COLORS[n.group] || '#8c8c8c',
          },
        }));

        const edges = projection.edges.map((e, idx) => ({
          id: `edge-${idx}`,
          source: e.source,
          target: e.target,
          data: { type: e.type },
        }));

        if (!containerRef.current) return;

        const graph = new Graph({
          container: containerRef.current,
          width: containerRef.current.offsetWidth,
          height: containerRef.current.offsetHeight || 600,
          data: { nodes, edges },
          // 力导向布局 + 聚类：同类节点聚拢形成色团（Obsidian 风格），
          // preventOverlap 防重叠，alpha 快速收敛避免持续抖动
          layout: {
            type: 'force',
            preventOverlap: true,
            nodeSize: 20,
            nodeStrength: -80,
            edgeStrength: 0.1,
            linkDistance: 100,
            gravity: 5,
            collideStrength: 0.8,
            alpha: 0.3,
            alphaDecay: 0.028,
            alphaMin: 0.001,
            // 按 group 聚类：同类型节点互相吸引，形成五色色团
            clustering: true,
            nodeClusterBy: (d: any) => d.data?.group || 'other',
            clusterNodeStrength: 15,
          },
          // 节点大小按连接数自适应（Obsidian：连接多的节点更大）
          transforms: [
            { type: 'map-node-size', centrality: 'degree', minSize: 12, maxSize: 40 },
          ],
          node: {
            type: 'circle',
            style: (model: any) => ({
              fill: model.data?.color || '#8c8c8c',
              // 标签默认隐藏，hover 时由 active state 显示
              labelText: '',
            }),
            // 状态样式：hover-activate 触发 active/inactive
            state: {
              active: {
                fill: (model: any) => model.data?.color || '#8c8c8c',
                labelText: (model: any) => model.data?.label || '',
                labelFontSize: 11,
                labelPosition: 'bottom',
                labelFill: '#262626',
                lineWidth: 2,
                stroke: '#fff',
              },
              inactive: {
                opacity: 0.15,
              },
            },
          },
          edge: {
            type: 'line',
            style: {
              stroke: '#d9d9d9',
              lineWidth: 1,
            },
            state: {
              active: {
                stroke: '#8c8c8c',
                lineWidth: 1.5,
              },
              inactive: {
                opacity: 0.05,
              },
            },
          },
          behaviors: [
            'drag-canvas',
            'zoom-canvas',
            'drag-element',
            // hover 高亮 1 度邻居 + 暗化其他（Obsidian 核心交互）
            {
              type: 'hover-activate',
              degree: 1,
              state: 'active',
              inactiveState: 'inactive',
            },
            // 缩放时优化视口外节点渲染，提升大图性能
            'optimize-viewport-transform',
          ],
          plugins: [
            // 小地图导航（Obsidian 风格）
            { type: 'minimap', size: [180, 120] },
          ],
        });

        // 点击节点展示详情（grilling 决策 6：属性不塞进投影，点击时另取）
        graph.on('node:click', (evt: any) => {
          const nodeId = evt.target?.id;
          if (nodeId) {
            handleNodeClick(nodeId);
          }
        });

        graphRef.current = graph;
        await graph.render();
        if (!cancelled) setLoading(false);
      } catch {
        if (!cancelled) {
          message.error('知识图谱加载失败');
          setLoading(false);
        }
      }
    }

    loadAndRender();

    // 窗口尺寸变化时防抖重绘（避免频繁触发完整布局重算）
    const handleResize = () => {
      if (resizeTimer) clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        if (graphRef.current && containerRef.current) {
          graphRef.current.setSize(
            containerRef.current.offsetWidth,
            containerRef.current.offsetHeight || 600,
          );
          graphRef.current.draw();
        }
      }, 300);
    };
    window.addEventListener('resize', handleResize);

    return () => {
      cancelled = true;
      if (resizeTimer) clearTimeout(resizeTimer);
      window.removeEventListener('resize', handleResize);
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
    };
  }, []);

  /** 渲染节点详情中的属性列表。 */
  function renderDetailProperties(d: GraphNodeDetail) {
    const items: { label: string; value: React.ReactNode }[] = [];
    if (d.node_type) {
      items.push({ label: '类型', value: <Tag color={GROUP_COLORS[d.node_type]}>{GROUP_LABELS[d.node_type] || d.node_type}</Tag> });
    }
    if (d.name) items.push({ label: '名称', value: d.name });
    if (d.name_snapshot) items.push({ label: '药品名', value: d.name_snapshot });
    if (d.aliases && d.aliases.length > 0) {
      items.push({ label: '别名', value: d.aliases.map((a) => <Tag key={a}>{a}</Tag>) });
    }
    if (d.ingredients && d.ingredients.length > 0) {
      items.push({ label: '成分', value: d.ingredients.map((i) => <Tag key={i} color="cyan">{i}</Tag>) });
    }
    if (d.allergen) items.push({ label: '过敏原', value: <Tag color="purple">{d.allergen}</Tag> });
    if (d.department) items.push({ label: '所属科室', value: d.department });
    if (d.description) items.push({ label: '描述', value: d.description });

    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {items.map((item, idx) => (
          <div key={idx}>
            <span style={{ color: '#8c8c8c', marginRight: 8 }}>{item.label}：</span>
            <span>{item.value}</span>
          </div>
        ))}
      </div>
    );
  }

  return (
    <PageContainer title="医学知识图谱">
      <div style={{ background: '#fff', padding: 16, borderRadius: 8 }}>
        {/* 图例 */}
        <div style={{ display: 'flex', gap: 16, marginBottom: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          {Object.entries(GROUP_LABELS).map(([group, label]) => (
            <span key={group} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: GROUP_COLORS[group] }} />
              {label}
            </span>
          ))}
          <span style={{ color: '#8c8c8c', fontSize: 12 }}>
            （悬停高亮关联节点，点击查看详情，滚轮缩放，拖拽平移）
          </span>
        </div>
        <Spin spinning={loading} tip="加载知识图谱...">
          <div
            ref={containerRef}
            style={{ width: '100%', height: '70vh', minHeight: 500, position: 'relative' }}
          >
            {!loading && !graphRef.current && (
              <Empty description="知识图谱暂无数据" style={{ paddingTop: 100 }} />
            )}
          </div>
        </Spin>
      </div>

      <Drawer
        title="节点详情"
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
          setDetail(null);
        }}
        width={400}
      >
        {detailLoading ? (
          <div style={{ textAlign: 'center', paddingTop: 40 }}>
            <Spin tip="加载中..." />
          </div>
        ) : detail ? (
          renderDetailProperties(detail)
        ) : (
          <Empty description="无详情" />
        )}
      </Drawer>
    </PageContainer>
  );
}
