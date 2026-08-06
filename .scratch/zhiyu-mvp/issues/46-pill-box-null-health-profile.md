# 46 — 拍药盒无健康档案时跨栈 `null` 导致服务不可用

**What to build:** 修复 C 端在没有激活健康档案时使用“拍药盒”会返回 502“药盒识别服务暂不可用”的问题。无档案是合法业务状态：server-py 应继续完成候选药名识别，server-java 以空过敏列表执行药品查询与安全检查；健康档案只能增强个性化过敏提醒，不得成为拍药盒的前置条件。

**Blocked by:** 无（14 - 拍药盒、21 - 健康档案均已完成）

**Status:** claimed

## 已确认根因

1. `HealthProfileService.agentContext(patientId)` 在没有激活档案时按设计返回 `null`。
2. `AgentClient.interpretVision(...)` 无条件执行 `objectMapper.writeValueAsString(healthProfile)` 并加入 multipart，因此跨栈请求携带的是字面字符串 `health_profile="null"`，而不是省略可选字段。
3. server-py `POST /api/agent/vision/interpret` 仅以“字段未传”表达无档案；只要字段存在，就调用 `HealthProfilePayload.model_validate_json(...)`。字面 `null` 无法校验为对象，抛出未捕获的 Pydantic `ValidationError`，返回裸 500。
4. server-java 将该上游失败折叠为 502，C 端最终展示“药盒识别服务暂不可用”。错误发生在方舟模型调用之前，与 MinIO、模型配额和药品数据无关。

## 复现证据（2026-08-06）

- 通过 C 端 mock 登录创建无健康档案的虚构患者，向 `POST /api/c/pill-box-photos` 上传合法 PNG：连续两次返回 `HTTP 502 {"detail":"药盒识别服务暂不可用"}`，耗时约 0.41–0.44 秒。
- 使用同一内部认证直调 server-py，并提交 `scenario=PILL_BOX`、`health_profile=null`：稳定返回裸 `HTTP 500 Internal Server Error`，耗时约 3 毫秒。
- TestClient 开启 `raise_server_exceptions=True` 后，traceback 定位到 `server-py/app/api/vision.py` 的 `HealthProfilePayload.model_validate_json(health_profile)`，异常为 `pydantic_core.ValidationError`。
- 现有 server-py 药盒测试通过，是因为无档案测试直接省略了 `health_profile`；现有 server-java `PillBoxPhotoServiceTest` 的成功路径则统一 mock 了非空档案。两端各自测试均未覆盖真实 multipart 的空值编码。

## 实施要求

- [x] 明确并统一视觉接口的可选档案契约：无档案时 server-java 不发送 `health_profile` multipart part；不得以字面 `null` 代替字段缺失。
- [x] server-py 对历史调用方可能传入的 JSON `null` 做兼容，等价视为无档案；其他畸形或不完整 JSON 必须返回契约化 4xx，禁止泄漏为裸 500。
- [x] 拍药盒无档案时继续完成视觉候选药名识别，并进入 `MedicationLookupService`；安全检查以空过敏列表降级，返回说明书和安全结果，文案仍说明未提供过敏史时无法完整确认。
- [x] 有激活档案时保持现有行为：档案上下文正常注入，server-java 使用当前档案过敏史执行确定性禁忌规则。
- [x] 检查共用 `AgentClient.interpretVision` 的 DIET/TONGUE 等允许无档案场景，确保同一 `null` 问题一并消除；不得扩大到要求健康档案的 REPORT/SKIN 产品入口。
- [x] 保持 MinIO 旁路语义：对象存储失败不阻断视觉主流程，本票不改变图片持久化策略。
- [x] 所有 AI 产出继续携带“仅供参考，不替代医生诊断”。

## 回归测试与验收

- [x] server-java 测试覆盖 `healthProfile == null` 时 multipart 不包含 `health_profile`，非空时仍序列化并发送完整对象。
- [x] server-py TestClient 覆盖 PILL_BOX 的三种输入：字段缺失、字面 `null`、合法档案对象；前两者均以 `document.health_profile is None` 进入 fake vision，合法对象仍正确注入。
- [x] server-py TestClient 覆盖畸形/不完整档案 JSON，断言返回结构化 422 而不是裸 500。
- [x] server-java HTTP 或跨栈集成测试覆盖“无档案患者上传药盒照片”，断言不再返回 502，并能得到 `not_found` 引导或 `medication_info` + `medication_safety` 双出口之一。
- [ ] 重跑原始复现：本地 server-java + server-py 下，无档案患者通过 `POST /api/c/pill-box-photos` 不再出现“药盒识别服务暂不可用”。
- [x] 运行 `mvn -f server-java/pom.xml test`、`mvn -f server-java/pom.xml spotless:check`、`uv run pytest`、`uv run ruff check server-py`、`uv run mypy server-py/app`、`uv run lint-imports`。
- [ ] 使用支付宝开发者工具人工走通“无档案登录 → AI 对话 → 拍药盒 → 选择照片 → 返回结果/合理的非药盒拒绝提示”，确认无控制台错误。
- [ ] 票单置 `done` 时，将 README 依赖图节点更新为 `T46["[x]46 拍药盒无档案修复"]`。

## Comments

- 2026-08-06（诊断）：确认这是跨栈可选字段编码不一致，不是“拍药盒业务必须依赖健康档案”。产品约定仍是：有档案时用过敏史增强安全提醒，无档案时正常查说明书并以空过敏列表降级。
- 2026-08-06（实施）：server-java `AgentClient` 抽取 `buildVisionMultipart`，档案为 null 时省略 `health_profile` part（DIET/TONGUE/SKIN/REPORT 共用，一并修复）；server-py 新增 `parse_optional_health_profile`（document.py），字面 `null` 兼容为无档案，畸形/不完整 JSON 抛契约码 `VISION_PROFILE_INVALID`（contracts/vision-errors.json 新增，双端钉死测试同步 15→16）。空过敏史 SAFE 文案改为 contracts/contraindication.json 新增 `safe_without_history`（“未提供过敏史，无法完整确认用药安全”），仅 MedicationLookupService 出口替换，B 端开方路径不受影响。测试：AgentClientTest 含真实 WebClient + JDK HttpServer 桩的 HTTP 级回归（无档案请求无 health_profile part、200 正常解析不再 502）；server-py TestClient 覆盖三种输入 + 两种畸形 JSON。全量检查通过（mvn 337 tests、spotless、pytest 137、ruff、mypy、lint-imports）。剩余人工项：本地双服务复现与支付宝开发者工具走查未执行（需人工环境）。
