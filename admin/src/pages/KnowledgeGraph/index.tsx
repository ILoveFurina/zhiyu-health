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
import PageHead from '@/components/PageHead';

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

// 边按关系类型着色（与 EDGE_LABELS 同 key），未匹配回退中性灰
const EDGE_COLORS: Record<string, string> = {
  INDICATES: '#ffa940',
  TREATED_BY: '#52c41a',
  SUGGESTS_DEPARTMENT: '#95de64',
  TREATS: '#36cfc9',
  CONTRAINDICATED_FOR: '#ff7875',
  INTERACTS_WITH: '#722ed1',
};

// 仿真坐标缓存：afterlayout 后存节点 {id -> {x,y}}，重进页面预填坐标跳过力仿真。
// 仅缓存坐标（不缓存业务数据），投影数据仍每次实时拉取以保证最新。
const LAYOUT_CACHE_KEY = 'zhiyu:knowledge-graph:layout';

/**
 * 清除图中所有 active 高亮态（节点描边 + 边标签）。
 * hover-activate 不使用 inactiveState，避免鼠标移到画布空白时淡色态残留；
 * 鼠标移开节点（onHoverEnd）、离开画布、点击空白时由此函数兜底清除 active。
 */
function clearActiveState(graph: Graph) {
  const states: Record<string, never[]> = {};
  graph.getElementDataByState('node', 'active').forEach((n: any) => (states[n.id] = []));
  graph.getElementDataByState('edge', 'active').forEach((e: any) => (states[e.id] = []));
  if (Object.keys(states).length > 0) {
    graph.setElementState(states, false);
  }
}

/**
 * 医学知识图谱可视化页（票 13）。
 *
 * 使用 @antv/g6 v5 力导向图渲染五类节点（症状/疾病/科室/药品/禁忌），
 * 节点点击展示详情（属性不塞进投影，点击时另取，grilling 决策 6）。
 * 数据经 server-java 鉴权后转调 server-py 只读接口（ADR-0013）。
 *
 * 交互参照 Obsidian 关系图：
 * - drag-element-force 拖动时实时重启 d3-force 仿真，相邻节点跟随移动；
 *   fixed:false 使松手后节点继续受力，整图保持“活力”平衡。
 * - hover-activate 悬停高亮一阶邻居并淡化其余，相关边显示关系标签。
 * - quadratic 曲线边按关系类型染色 + collide 防重叠，缓解线条相交拥挤。
 * - afterlayout 后缓存仿真坐标到 sessionStorage，重进页面预填坐标并将
 *   d3-force 的 alpha 设为等于 alphaMin 使仿真立即收敛（跳过约 300 次迭代），
 *   大幅减少重复渲染耗时；拖动时仍可重启仿真联动。
 * - 抽屉关闭、鼠标移开节点、离开画布时主动清除 active 高亮态，避免残留。
 *   不使用 inactiveState：G6 v5.1.1 的 inactive 状态在鼠标移到画布空白时
 *   清除不可靠，会导致全图卡在淡色。
 */
export default function KnowledgeGraphPage() {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<GraphNodeDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function loadAndRender() {
      try {
        const projection = await fetchGraphProjection();
        if (cancelled) return;

        if (!projection.nodes || projection.nodes.length === 0) {
          setLoading(false);
          return;
        }

        // 命中缓存时预填仿真坐标，跳过力仿真（投影数据仍实时拉取）
        const cachedPositions = (() => {
          try {
            const raw = sessionStorage.getItem(LAYOUT_CACHE_KEY);
            return raw ? (JSON.parse(raw) as Record<string, { x: number; y: number }>) : null;
          } catch {
            return null;
          }
        })();
        const hasCache = !!cachedPositions;

        // G6 v5 数据格式：节点带 data 字段，边带 source/target/data
        const nodes = projection.nodes.map((n: GraphNode) => {
          const node: {
            id: string;
            data: { label: string; group: string; color: string };
            style?: { x: number; y: number };
          } = {
            id: n.id,
            data: {
              label: n.label,
              group: n.group,
              color: GROUP_COLORS[n.group] || '#8c8c8c',
            },
          };
          if (cachedPositions?.[n.id]) {
            node.style = { x: cachedPositions[n.id].x, y: cachedPositions[n.id].y };
          }
          return node;
        });

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
          // 力导向布局：节点互斥 + 边吸引 + 防重叠，适合知识图谱可视化。
          // 命中缓存时节点已带预填坐标，将 alpha 设为等于 alphaMin 让 d3-force
          // 仿真立即收敛（不迭代），直接采用缓存坐标，跳过约 300 次迭代的开销；
          // 拖动时 drag-element-force 仍可重启仿真联动。
          layout: {
            type: 'd3-force',
            manyBody: { strength: -150 }, // 适度斥力，避免抱团
            link: { distance: 100, strength: 0.6 }, // 稍长边距，缓解视觉拥挤
            // 防节点重叠：与节点实际 size 一致，多次迭代更稳定
            preventOverlap: true,
            nodeSize: 24,
            collide: { strength: 0.8, iterations: 3 },
            // 命中缓存：仿真立即收敛；否则约 300 次迭代
            ...(hasCache ? { alpha: 0.001, alphaMin: 0.001, alphaDecay: 1 } : { alphaDecay: 0.028 }),
          },
          node: {
            type: 'circle',
            style: (model: any) => ({
              size: 24,
              fill: model.data?.color || '#8c8c8c',
              labelText: model.data?.label || '',
              labelFontSize: 11,
              labelPosition: 'bottom',
              labelFill: '#595959',
            }),
            // 仅用 active 高亮态：不使用 inactiveState（G6 v5.1.1 的 inactive
            // 状态在鼠标移到画布空白时清除不可靠，会导致全图卡在淡色）。鼠标移开时
            // 由 onHoverEnd 回调 + canvas:pointerleave 兜底清除 active 态。
            state: {
              active: { lineWidth: 2, stroke: '#1890ff' },
            },
          },
          edge: {
            type: 'quadratic',
            style: (model: any) => {
              const type = model?.data?.type;
              return {
                stroke: EDGE_COLORS[type] || '#d9d9d9',
                lineWidth: 1,
                curveOffset: 25, // 弯曲程度，缓解两节点间多条直线重叠
                labelText: (type && EDGE_LABELS[type]) || '',
                // 默认隐藏标签，悬停时由 active 态显示
                label: false,
                labelFontSize: 10,
                labelFill: '#595959',
                labelBackground: true,
                labelBackgroundFill: '#fff',
                labelBackgroundOpacity: 0.85,
                labelPadding: [2, 4],
              };
            },
            // 悬停态：显示关系标签 + 加粗
            state: {
              active: { label: true, lineWidth: 2 },
            },
          },
          behaviors: [
            'drag-canvas',
            'zoom-canvas',
            // 力导向专用拖拽：拖动时实时重启 d3-force 仿真，相邻节点跟随移动；
            // fixed:false 使松手后节点继续受力，整图保持 Obsidian 式“活力”平衡
            { type: 'drag-element-force', fixed: false },
            // 悬停高亮一阶邻居（Obsidian 标志性交互）。不用 inactiveState，避免
            // 鼠标移到空白时淡色态残留；移开时由 onHoverEnd 主动清除 active 态。
            {
              type: 'hover-activate',
              degree: 1,
              direction: 'both',
              state: 'active',
              onHoverEnd: () => clearActiveState(graph),
            },
          ],
        });

        // G6 v5 事件经 EventEmitter 绑定（非 options.events）：
        // 点击节点展示详情（grilling 决策 6：属性不塞进投影，点击时另取）
        graph.on('node:click', (evt: any) => {
          const nodeId = evt.target?.id;
          if (nodeId) {
            handleNodeClick(nodeId);
          }
        });

        // 鼠标离开画布（移到空白或移出画布范围）时兜底清除 active 高亮态，
        // 确保不会残留高亮（hover-activate 的 onHoverEnd 已处理节点间切换）。
        graph.on('canvas:pointerleave', () => clearActiveState(graph));
        graph.on('canvas:click', () => clearActiveState(graph));

        // 仿真收敛后缓存节点坐标，重进页面可预填跳过力仿真（仅首次无缓存时存）。
        // 拖动改变位置后缓存仍会随 afterlayout 持续刷新，保持最新。
        if (!hasCache) {
          graph.on('afterlayout', () => {
            try {
              const positions: Record<string, { x: number; y: number }> = {};
              graph.getNodeData().forEach((n: any) => {
                const { x, y } = n.style || {};
                if (x != null && y != null) positions[n.id] = { x: +x, y: +y };
              });
              if (Object.keys(positions).length > 0) {
                sessionStorage.setItem(LAYOUT_CACHE_KEY, JSON.stringify(positions));
              }
            } catch {
              // 缓存写入失败不影响渲染
            }
          });
        }

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

    // 窗口尺寸变化时重绘
    const handleResize = () => {
      if (graphRef.current && containerRef.current) {
        graphRef.current.setSize(
          containerRef.current.offsetWidth,
          containerRef.current.offsetHeight || 600,
        );
        graphRef.current.render();
      }
    };
    window.addEventListener('resize', handleResize);

    return () => {
      cancelled = true;
      window.removeEventListener('resize', handleResize);
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
    };
  }, []);

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
    <PageContainer header={{ title: null }}>
      <PageHead
        title="医学知识图谱"
        description="可视化症状、疾病、科室、药品与禁忌的关联关系，点击节点查看详情"
        tags={['力导向图', '五类节点']}
      />
      <div style={{ background: '#fff', padding: 16, borderRadius: 8 }}>
        {/* 图例 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
          {/* 节点类型图例 */}
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            {Object.entries(GROUP_LABELS).map(([group, label]) => (
              <span key={group} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: GROUP_COLORS[group] }} />
                {label}
              </span>
            ))}
          </div>
          {/* 关系类型图例（与曲线边配色一致） */}
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            {Object.entries(EDGE_LABELS).map(([type, label]) => (
              <span key={type} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ display: 'inline-block', width: 16, height: 2, background: EDGE_COLORS[type] || '#d9d9d9' }} />
                {label}
              </span>
            ))}
          </div>
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
          // 抽屉关闭时清除 hover 残留的高亮态，恢复正常显示
          if (graphRef.current) clearActiveState(graphRef.current);
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
