# 39 - 医生管理页：挂号费字段编辑

**What to build:** B 端医生管理页表单新增"挂号费"字段编辑（doctors.registration_fee），让管理员可维护各职称医生的诊查费定价。此票为挂号收费模块的定价管理面，极小切片，依赖 35 已加好的 schema 字段。

**Blocked by:** 35 - 挂号收费：诊查费字段与挂号即欠费

**Status:** ready-for-agent

- [ ] B 端医生管理页（`admin/src/pages/Doctor/`）表单增"挂号费"字段编辑，保存到 doctors.registration_fee
- [ ] 医生管理接口的入参 DTO 与映射支持 registration_fee 字段（MapStruct）
- [ ] MockMvc 验证：编辑挂号费后医生列表返回正确数据
- [ ] 浏览器实测无控制台错误，人工走通"B 端医生管理编辑挂号费"
