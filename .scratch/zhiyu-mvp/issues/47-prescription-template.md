# 47 - 处方模板管理：常用药品组合

**What to build:** B 端医生可维护个人处方模板（模板名称 + 药品明细：药品/用法用量/频次/疗程/备注，与 `prescription_items` 同构），医生工作台开方时可从模板一键导入预填处方明细；模板按 doctor_id 归属，医生仅见自己的模板。覆盖题目 B 端"处方模板管理（常用药组合）"，对应评分项"B 端医生操作是否高效"。

**Blocked by:** 09 - 电子处方（明细结构同构，已完成）

**Status:** claimed

- [x] `schema.sql`：`prescription_templates`（id/name/doctor_id/created_at）+ `prescription_template_items`（template_id/medication_id/dosage/frequency/duration/notes，与 `prescription_items` 同构）；seed 为 doctor.lin / doctor.zhou 各备 1-2 个虚构常用药组合（drop + recreate + seed，不用迁移工具）
- [x] server-java 模板 CRUD 接口（controller 归 `controller/b/`，service 继承 MyBatis-Plus ServiceImpl，DTO 映射用 MapStruct）：列表按当前登录医生过滤，禁止跨医生读写
- [x] admin 医生工作台（Workbench）：开方面板加"从模板导入"（选中模板预填明细，导入后可再编辑）+ 模板管理入口（新建/编辑/删除）
- [x] MockMvc：模板 CRUD、医生数据隔离（doctor.lin 读不到 doctor.zhou 的模板）、停用药品不出现在模板选药列表（沿用票 34 口径）
- [ ] 浏览器实测无控制台错误，人工走通"建模板 → 开方导入 → 提交处方"
