# 34 - 药品管理：价格库存字段与 B 端管理面

**What to build:** medications 表新增价格与库存字段并补齐 seed；B 端新增药品管理页，管理员可查看药品列表（名称/通用名/规格/价格/库存/状态）并编辑价格、库存、上下架；停用的药品不再出现在医生开方选药列表中。此票为药品订单模块的前置依赖（订单需用 price 算总价、stock 做预扣），本身独立可演示（调价/补库存/上下架）。

**Blocked by:** None - can start immediately

**Status:** done

- [x] medications 表新增 `price DECIMAL(10,2)` 与 `stock INT` 字段；seed 补齐现有 30 条药品的价格与库存（drop + recreate + seed，不用迁移工具）
- [x] 新增 B 端药品管理接口（列表 + 编辑价格/库存 + 上下架 is_active），controller 归 `controller/b/`，service 继承 MyBatis-Plus ServiceImpl，DTO 映射用 MapStruct
- [x] B 端新增药品管理页（`admin/src/pages/Medication/`）：列表展示 + 编辑价格/库存 + 上下架操作；routes.ts 追加菜单项并加入 ADMIN_PATHS 限制 doctor 角色
- [x] 停用（is_active=false）的药品不出现在医生开方选药列表（DoctorPrescriptionController 的 medications 接口仅返回 is_active=true）
- [x] MockMvc 验证：编辑价格/库存后列表返回正确数据；上下架后开方选药列表可见性正确变化
- [x] 浏览器实测无控制台错误，人工走通"药品管理调价/补库存/上下架"
