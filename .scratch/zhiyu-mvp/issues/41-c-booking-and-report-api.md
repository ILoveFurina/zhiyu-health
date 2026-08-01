# 41 - C 端预约挂号浏览/直接挂号与报告解读历史 API

**What to build:** 为小程序"功能目录"（见 CONTEXT.md）提供独立入口所需的 C 端只读浏览接口与直接挂号接口：医院/科室/医生/排班逐级浏览，C 端不经 Agent 直接创建挂号单；另补报告解读历史记录查询接口。挂号收费（payments）不在本票范围，属票 35/36。

**Blocked by:** 无（07 挂号闭环、37 药品订单下单均已完成）

**Status:** ready-for-agent

- [ ] `GET /api/c/hospitals`：医院列表（含经纬度字段；可选 lat/lng 参数按距离排序，复用 Agent 侧 nearby 的排序逻辑）
- [ ] `GET /api/c/hospitals/{hospitalId}/departments`：某医院科室列表
- [ ] `GET /api/c/departments/{departmentId}/doctors`：某科室医生列表（含职称、挂号费 registration_fee）
- [ ] `GET /api/c/doctors/{doctorId}/schedules`：某医生未来排班及剩余号源
- [ ] `POST /api/c/appointments`：C 端直接挂号（传 schedule_id），与 Agent 工具 `/api/agent/appointments` 复用同一 service；号源扣减只经 `SlotAccounting`（Redis 原子 DECR + PostgreSQL 事务对账，禁止先查后改）；不创建 payments 记录（属票 35）
- [ ] `GET /api/c/report-interpretations`：当前患者的报告解读记录列表（倒序），供小程序报告解读入口页展示
- [ ] 状态、决定、消息类型等契约值只从 `contracts/` 加载，禁止硬编码
- [ ] MockMvc：浏览接口 200 与未登录 401；直接挂号成功扣减号源；号源耗尽/重复挂号返回明确业务错误；报告解读历史只返回当前患者数据
