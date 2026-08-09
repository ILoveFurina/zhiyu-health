# 75 - server-py 购药工具与 agent 接线

**What to build:** server-py 新增 3 个业务工具回调 server-java 只读 agent 端点，为 AI 购药提供数据获取能力。① `search_medications`：按药名模糊查询 OTC 药品（`is_prescription=FALSE`），供用户点名买药时查药；② `list_approved_prescriptions`：查当前患者已审核（APPROVED）电子处方，返回带药品明细（药名/规格/数量/用法用量）的处方视图，供处方药购药选处方；③ `prepare_drug_order`：按 medication_id（OTC）或 prescription_id（处方药）装配购药确认卡所需数据（实时单价/库存可用性/总价测算），不扣库存、不建订单。三个工具的签名、参数校验、错误文案与现有挂号四件套（recommend_doctors/get_doctor_slots/create_appointment/get_appointment）同构，经 `BusinessCallbackClient` 转发。server-java 侧新增对应 `/api/agent/**` 只读端点。此票只通工具层，不下沉到卡片渲染（卡片在 76/77）。

**Blocked by:** 74 - 药品处方属性与订单处方可空（schema 与 service 基线）

**Status:** done

- [x] server-java 新增 `/api/agent/medications` 只读端点：参数 name（模糊）/is_prescription（布尔过滤），返回 medication_id/name/generic_name/specification/price/stock/is_active；复用 AdminInterceptor 之外的 agent 鉴权（与现有 /api/agent/doctors/recommend 同一鉴权层）
- [x] server-java 新增 `/api/agent/prescriptions` 只读端点：参数 patient_id + status=APPROVED，返回处方列表含药品明细（medication_id/name/specification/quantity/dosage/frequency/duration）+ 开方医生姓名 + 来源（appointment_id/online_consultation_id 派生展示）；患者归属校验
- [x] server-java 新增 `/api/agent/drug-orders/prepare` 只读端点：入参 medication_id+quantity（OTC）或 prescription_id（处方药），返回确认卡数据（药品明细/单价/库存可用性/总价/处方来源）；只读不扣库存
- [x] server-py tools/business.py 新增三个 @tool：search_medications(name)->/api/agent/medications、list_approved_prescriptions(runtime)->/api/agent/prescriptions、prepare_drug_order(...)->/api/agent/drug-orders/prepare；经 _forward_get 转发，错误文案与挂号工具同构
- [x] server-py build_business_tools 装配三个新工具；agent runner/prompt 接线（购药意图识别 -> 选工具）
- [x] server-py TestClient 测：三个工具成功路径返回结构正确；search_medications 只返回 is_prescription=FALSE；list_approved_prescriptions 只返回 APPROVED 且归属当前患者；prepare 不扣库存（medications.stock 无变化）
- [x] 工具调用经 tool_start/tool_end trace 事件可见（票 24 机制复用，不新增 trace 契约）
- [x] README.md 依赖关系图新增节点 T75（未完成不加 [x]）
