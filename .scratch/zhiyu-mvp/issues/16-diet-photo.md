# 16 - 拍饮食

**What to build:** 患者拍摄饮食照片 -> 复用视觉管道 -> 营养与热量估算 + 饮食建议卡片，带免责声明标注。

**Blocked by:** 12 - 报告解读与视觉管道；21 - 健康档案

**Status:** done

本票是 15 拍皮肤建立的可复制模板的**第一个照搬票**。视觉管道泛化、MinIO 旁路持久化、上传链路、卡片回落会话、契约同步等 15 已建立的模式直接复用，不重复记录。以下只记 16 的**差异化点**。

## 差异化点：结合健康档案的个性化一句提醒

16 相对 15 的唯一差异化需求：票单要求"结合健康档案（如有）给出个性化一句提醒"。

### 决策：只用现有档案字段，不扩表（grilling 确认）

探查确认 `health_profiles` 表当前只有 7 个业务字段（display_name/gender/birth_date/relationship/active + 过敏史关联表），**没有任何饮食相关字段**（无慢病/BMI/饮食偏好）。grilling 期间曾考虑扩档案加慢病标记支撑"糖尿病->低糖"类真正个性化饮食提醒，但评估后认为扩表牵动 schema/seed/实体/mapper/CreateCommand/前端表单/多个消费方（报告解读/对话/处方/禁忌/服药打卡），与两周 demo 收益不成比例，故放弃扩表，回到只用现有字段。

个性化上限为**过敏史驱动的食材风险提示 + 年龄/性别通用话术**：

- [x] server-py 饮食 prompt 注入当前激活档案的过敏史（复用 `HealthProfilePayload.allergies`，参照报告解读 `interpreter.py:107-119` 的档案注入先例），让 LLM 在识别出食材后比对过敏原，命中则产出"检测到你对{过敏原}过敏，本餐含{食材}，请注意"风险提示
- [x] 从 `birth_date` 推算年龄，作为 prompt 上下文（如老人/儿童饮食注意事项），不存派生列
- [x] 无激活档案时（档案未创建），饮食分析仍正常完成，仅缺个性化提醒句--与"未创建档案时 AI 页显示引导卡片"的既有约定一致
- [x] 个性化提醒**只是额外一句**，不改变饮食卡片主体结构（营养估算/热量/饮食建议仍为通用分析）

### 边界：不扩档案表

- [x] **不**新增 chronic_conditions 字段或 health_profile_chronic_conditions 表；不做"糖尿病->低糖""高血压->低盐"类慢病导向饮食提醒。两周 demo 范围内饮食个性化止于过敏史 + 年龄/性别。

## 皮肤模板照搬项（15 已定，此处仅勾选确认）

- [x] `scenarios.py` 注册 `"DIET"` key，绑饮食 prompt + `DietAnalysis` result_model
- [x] `AgentClient.java:148` scenario 参数化后传 `"DIET"`
- [x] MinIO 旁路持久化：原图存 MinIO + `messages.kind=image` 存路径（15 已建模式）
- [x] 饮食分析结果卡片作为独立 AI 消息回落会话（`kind=diet_analysis`，落 `messages.content`）
- [x] 免责声明标注（硬规则 1）
- [x] 会话 composer 加"拍饮食"入口
- [x] C 端 `index.axml` 加 `diet_analysis` 卡片渲染分支（优先抽成 `components/diet-card` 组件）
- [x] `miniprogram/utils/message-kinds.js` 注册 `diet_analysis` kind
- [x] `contracts/sse-events.json` 的 `message_kinds` 新增 `diet_analysis`，双端同步
- [x] 功能落地后在票 19 功能入口气泡配置中点亮"拍饮食"（`feature-bubbles.js` 对应项 `enabled:true` 并接上 action）

## Comments

- 2026-08-04（grilling）：确认 16 照搬 15 模板，唯一差异化是"结合档案个性化一句提醒"。曾考虑扩档案加慢病标记，因牵动面过广放弃，个性化止于过敏史+年龄/性别。决策记录见 ADR-0023（图片持久化）与本票注释。
