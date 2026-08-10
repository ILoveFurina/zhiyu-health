# 88 - 院区药房库存与模拟履约闭环

**What to build:** 将现有“全局药品库存 + 平台中心药房 + Mock 外部药店同步”重构为多医院、多院区下的真实业务模型：每个院区自动拥有且仅拥有一个院区药房，药房独立维护处方药/OTC 的价格、库存和在售状态；处方固化开方院区并锁定该院区药房，OTC 由患者在当前服务城市内选择可整单履约的院区药房。统一购药确认页完成实时校验、库存预扣、10 分钟待支付和一次性处方核销；一个全局药师账号在 B 端完成处方审核、库存维护与配送/自取状态推进，C 端展示订单与模拟履约时间线，并在实际交付后生成处方用药提醒。ADR-0035、`CONTEXT.md` 与 `docs/research/pharmacy-fulfillment-integration.md` 是本票决策依据。

**Blocked by:** 76 - 药品处方属性与订单处方可空、77 - server-py 购药工具与 Agent 接线、78 - 购药卡片契约与 SSE 事件、79 - C 端购药卡片渲染与两段式确认、80 - 处方药选处方交互（均已 done）

**Status:** claimed

## 契约与数据模型

- [x] contracts：新增药品订单状态、取药方式、履约动作/事件和 B 端 staff role 的单一事实源；Java/TypeScript/小程序枚举与权限判断均从契约推导，`ContractsTest`/跨栈一致性测试同步
- [x] schema：新增 `campus_pharmacies`（`campus_id` 唯一且非空、展示名、固定配送费、预计配送分钟数）与 `pharmacy_medications`（`pharmacy_id + medication_id` 唯一、价格、库存、在售状态），价格非负、库存非负、预计时效为正数
- [x] schema：院区与药房保持强一对一；新建院区时在同一事务自动创建药房，院区药房不可从独立 CRUD 新增或删除，仅允许修改展示名、配送费与预计时效
- [x] schema：`medications` 收敛为全局标准药品目录（名称、规格、处方属性及必要知识字段），移除全局价格/库存语义；现有虚构 seed 为每个院区生成一个药房和相互独立的药房药品数据
- [x] schema：`prescriptions` 新增不可变来源院区；`prescription_items` 与 `prescription_template_items` 新增正整数配药数量；从已鉴权接诊医生派生来源院区，禁止客户端传入或后续跟随医生调动
- [x] schema：扩展 `drug_orders`，至少固化履约药房、取药方式、支付截止、药品金额、配送费/总额、药房/院区/自取地址快照、脱敏展示所需字段、配送收货信息快照、虚构承运方/物流单号及各关键状态时间戳；自取订单不得落收货信息
- [x] schema：订单明细关联标准药品并保存药房药品关系/成交单价与数量快照；新增 append-only `drug_order_fulfillment_events`，记录状态、发生时间和操作 staff，不允许更新或删除历史事件
- [x] schema：为处方建立“同一处方至多一张非 `CANCELLED`/`EXPIRED` 订单”的数据库约束；支付时固化处方核销时间/订单，支付后永久阻止二次购药
- [x] schema：药房药品从未被处方或订单引用时才允许物理删除，已有历史引用只允许下架/重新上架；采用外键与 service 检查共同保障，历史处方和订单展示不依赖当前在售关系

## server-java 业务后端

- [x] 组织管理：扩展院区创建事务以自动创建院区药房；提供 admin 的药房基础配置接口，不暴露独立新增/删除药房端点
- [x] 药房库存：B 端按医院/院区选择药房，支持将标准药品加入药房、维护价格/库存/在售状态及符合历史引用规则的删除；新 CRUD service 继承 `ServiceImpl`，DTO/Entity/View 映射使用 MapStruct
- [x] 开方：线下接诊与在线问诊共用来源院区派生；医生只能选择自己当前院区药房已配置且在售的标准药品，处方与模板均由医生填写配药数量，患者不可改处方药数量；瞬时库存不足不阻断开方
- [x] 购药查询：处方药预览返回处方固化院区和锁定药房；OTC 候选只返回当前服务城市内、在售且能完整满足整篮数量的院区药房，有定位时提供真实坐标距离排序，无定位时按医院/院区稳定排序且不伪造距离、不默认选中
- [x] 下单：处方药强制使用处方来源院区药房并完全复用处方数量；OTC 强制无处方且仅含非处方药，由患者显式提交候选药房和数量；两者均须单药房整单满足，不拆单、不替代、不跨院区调货
- [x] 下单：事务内锁定所选药房药品行，重新校验关联、在售、成交价与全量库存后整单原子预扣并创建订单；任一项失败不落订单、不发生部分扣减，禁止先查后改
- [x] 处方防重：创建订单时防止同处方已有未取消/未过期订单；并发请求由数据库唯一约束兜底，返回可理解的 409，不依赖前端禁用
- [x] 待支付：创建即为 `UNPAID`，支付截止为 10 分钟；list/detail/pay/cancel 入口统一惰性收敛过期订单至 `EXPIRED`，在同一事务回补库存且释放处方重试资格，不引入 scheduler
- [x] 支付：仅 `UNPAID -> PAID`，条件更新与处方一次性核销在同一事务；已核销处方、过期/取消订单和重复支付均确定性拒绝，支付后无取消、退款、拒收、配送失败、补发或恢复处方分支
- [x] 取消：仅 `UNPAID -> CANCELLED`，状态条件更新、全量库存回补和事件记录同事务且幂等；其他状态不得取消
- [x] 模拟履约：配送只允许 `PAID -> DISPENSING -> SHIPPED -> DELIVERED`，自取只允许 `PAID -> DISPENSING -> READY_FOR_PICKUP -> PICKED_UP`；每次条件更新、状态时间戳和 append-only 事件同事务完成
- [x] 模拟履约：进入 `SHIPPED` 时生成虚构承运方“智愈模拟配送”、唯一虚构物流单号及订单预计时效；不实现真实物流、地图、骑手位置、定时自动推进或异常售后
- [x] 用药提醒：移除“处方审核通过即生成”行为，仅处方药订单第一次到达 `DELIVERED`/`PICKED_UP` 后按医生填写的用法、频次、疗程幂等生成；OTC 永不自动生成，Agent 不参与决策
- [x] 隐私：配送收货人、手机号和详细地址只保存为该订单快照，不建立地址簿、不写健康档案/聊天/Agent trace；C 端历史接口返回脱敏手机号和地址，B 端履约接口仅向 admin/pharmacist 返回完成履约必要的明文
- [x] 移除/替换票 48 的 `/api/b/demo/**` Mock 外部药店库存同步与静态 fixture；不得保留会误导为真实外部药店接入的入口，原有测试改写为院区药房库存隔离与现场补货测试

## 全局药师角色与 B 端

- [x] staff role 新增 `pharmacist`，沿用 `staff_users`，不新增 pharmacist 档案表、不绑定医院/院区；种子账号 `pharmacist/pharmacist123456`，密码支持 `SEED_PHARMACIST_PASSWORD` 覆盖
- [x] 权限边界：admin 可访问组织/系统以及处方审核、院区药房库存、药品订单；pharmacist 只可访问处方审核、院区药房库存、药品订单；doctor 只可访问接诊/排班/开方，不得访问审核、库存和履约
- [x] server-java 将现有 `/api/b/**` blanket admin 判断收敛为可复用的角色授权机制，按路由显式声明 admin-only、admin-or-pharmacist、doctor-only；controller 仍保持薄入口，不在页面隐藏上寄托安全性
- [x] 登录与种子：更新 `StaffUserSeed`、main/test `application.yml`、`.env.example`、seed 单测、`verify_zhiyu.py` 的 staff 基线及 `AGENTS.md` 演示账号说明；不得打印或提交真实 `.env` 值
- [x] admin：Role 类型、access、路由守卫、菜单与登录落点支持 pharmacist；pharmacist 登录后进入 `/prescriptions`，菜单仅展示“处方审核 / 院区药房库存 / 药品订单”，手输越权 URL 也被前后端拒绝
- [x] admin 药品管理改为院区药房库存页：医院→院区级联选择，展示/编辑该药房名称、配送报价和药品价格/库存/在售；支持现场从标准药品目录补一种药并在刷新后立即供医生开方、供患者查询
- [x] admin 处方审核保留现有禁忌确定性检查；admin/pharmacist 均可审核，doctor 不可审核自己的或他人的处方
- [x] admin 药品订单页按配送/自取展示合法下一步操作、必要收货信息、状态时间线和条件更新冲突提示；只允许 admin/pharmacist 推进，不提供任意状态下拉框

## server-py Agent 层与卡片

- [x] 购药工具契约改为药房感知：处方预览包含锁定院区药房，OTC 只在用户明确给出药名与数量后查询完整可履约候选；业务查询/写入继续只回调 server-java，server-py 不直写 PostgreSQL
- [x] `drug_order_confirm` 保留 message kind 但语义改为“购药预览卡”：只持久化来源、药名/规格/数量、处方医生/日期/医院/院区/锁定药房及“价格库存以确认页为准”，不包含收货人、电话、地址、取药方式、物流或最终承诺
- [x] 点击预览卡只跳统一购药确认页，不在聊天内提交订单；下单成功后追加非敏感订单结果卡，刷新历史会话仍可点击且不泄露地址
- [x] server-py TestClient 用 fake LLM/业务回调覆盖 OTC 明确药品+数量、症状不荐药、处方单/多/零态、锁定药房和预览卡跳转 payload，并断言工具调用顺序与“仅供参考，不替代医生诊断”

## C 端小程序

- [x] 新增/改造统一购药确认页，Agent 预览卡和处方详情页都跳到同一入口；每次进入/提交实时读取价格、库存与候选，不信任聊天卡片旧数据
- [x] 处方药确认页展示处方医生、来源医院/院区、不可更改的院区药房和数量；OTC 确认页要求用户显式选择当前服务城市内可整单履约药房，不预选第一家
- [x] OTC 已授权定位时按距离升序展示并标价格、距离、固定配送时效；未授权时按医院/院区稳定排序、不显示虚构距离，绝不跨城市补候选
- [x] 所有药房同时提供“院区自取 / 配送到家”：自取展示院区地址且配送费为 0；配送展示药房固定配送费/时效，并在提交前采集一次性收货人、手机、完整地址与授权确认，不建立地址簿
- [x] 订单列表/详情展示配送与自取各自状态、10 分钟支付倒计时/过期事实、价格快照和履约时间线；配送展示虚构承运方/单号，历史收货信息只显示脱敏值
- [x] 仅 `UNPAID` 显示模拟支付与取消；其他状态不展示伪造操作。已送达/已取药后展示已创建的处方用药提醒入口，OTC 不展示自动提醒

## 验收与文档

- [x] server-java service 单测覆盖院区建药房、跨院区库存隔离、医生开方目录/数量、整单原子扣减、取消/过期回补、处方并发防重与支付核销、非法状态跳转、提醒幂等及敏感字段脱敏
- [x] server-java 规则测试保留并扩展处方禁忌危险输入触发/正常输入不误触；每模块一条 MockMvc 主链路冒烟，新增角色负向 HTTP 测试覆盖 pharmacist 越权组织/日志、doctor 越权审核/库存/履约、patient 越权 B 端
- [ ] PostgreSQL 集成测试（`-Dpg.it=true`）覆盖一院区一药房唯一约束、处方活跃订单唯一约束、并发库存不超卖/不部分扣减、并发状态条件更新和库存回补恰好一次
- [x] 受影响 server-java 测试 + `ContractsTest`、server-py 购药测试、ruff/mypy/import lint、admin typecheck/build 通过；不删除存量测试，将 Mock 药店断言改为新业务边界
- [ ] 前端必须浏览器/支付宝开发者工具实测无控制台错误：分别以 admin、pharmacist、doctor 登录核对菜单/越权；人工走通处方药配送、处方药自取、OTC 选药房、待支付过期重下、B 端现场补药后刷新开方/下单、两条履约终态与提醒生成
- [x] 更新 `CONTEXT.md`、ADR-0026（superseded）、ADR-0035、研究结论引用和相关工程说明；删除所有“平台中心药房/外部 Mock 药店同步/审核通过即提醒”的过期口径
- [x] schema 完成后运行 `uv run python scripts/reset_zhiyu.py`；重启 server-java 让 `StaffUserSeed` 补种药师账号，再运行 `uv run python scripts/verify_zhiyu.py` 只读验证 schema、虚构 seed 和 staff 基线
- [ ] 票单置 done 前：README 依赖图 `T88` 节点加 `[x]`；未完成时保持数字开头

## Comments

- 2026-08-10 grill-with-docs（grilling + domain-modeling）确认：不做中心药房和外部药店；每院区一药房且同时经营处方药/OTC；处方锁开方院区，OTC 患者跨本城市院区自主选；整单单药房原子履约；10 分钟未支付惰性过期；支付时一次核销处方；配送/自取由 B 端手工推进；交付后才生成处方提醒；预览卡保留必要非敏感信息并跳确认页；现场可由 admin/pharmacist 给任意院区药房补标准药品。
- 2026-08-10 阶段一完成（契约/schema/seed/脚本/跨栈绑定机械同步）：order-flow.json 重构为九值状态机 + 取药方式 + 履约动作 + 600s 待支付 + 虚构承运方；新增 staff-roles.json（admin/doctor/pharmacist 小写取值沿用现有惯例）；schema 新增 campus_pharmacies/pharmacy_medications/drug_order_fulfillment_events，drug_orders 重构快照模型并加处方活跃订单部分唯一索引，prescriptions 补 source_campus_id 与核销字段，medications 移除 price/stock/is_active；seed 每院区一药房 + 150 行相互独立的药房药品；StaffUserSeed 补种全局药师（SEED_PHARMACIST_PASSWORD 缺省 pharmacist123456）；B 端「确认完成」（/complete + DONE）随 DONE 状态移除，履约推进待阶段二（代码内 TODO 标注）。数据库重建（reset_zhiyu.py）推迟到 server-java 业务代码对齐后执行。
- 2026-08-10 四栈分阶段提交完成（分支 feat/88-campus-pharmacy-fulfillment）：`99039c9` 契约/schema、`9d71801` admin 角色矩阵+药房库存/订单页、`10f1abd` textbook 口径、`e84d200` server-py 药房感知购药工具+预览卡白名单、`548f162` 小程序统一确认页+订单履约展示、`9f1994e` server-java 全量（角色三档/院区建药房/库存 CRUD/开方目录与数量/订单原子预扣/惰性过期/支付核销/履约双状态机+append-only 事件/交付后提醒幂等/脱敏/票48 Mock 药店移除）、`81c16a8` 修复 C 端下单收货信息为扁平三字段（活库冒烟发现嵌套形状与小程序不一致）、`47c37ea` AGENTS.md 药师账号。验证：mvn test 850 全绿+spotless、pytest 230 全绿+ruff/mypy/lint-imports、admin typecheck/build；reset_zhiyu.py + 重启 server-java + verify_zhiyu.py 全过（6 院区 6 药房、150 药房药品、staff 17 含 pharmacist）；活库冒烟通过（pharmacist 登录/越权 403、药房配置与库存、OTC 配送全链路 UNPAID→PAID→DISPENSING→SHIPPED→DELIVERED 含承运方单号与脱敏时间线、自取链路、取消回补、重复支付/非法跳转/重复取消均 409）。两个说明：① PG 集成测试（一院区一药房唯一/活跃订单部分唯一/并发条件更新恰好一次）已写入 PrescriptionSourcePgIntegrationTest，`-Dpg.it=true` 门控默认跳过，本次未实跑（需一次性库）；② 存量测试 PrescriptionServiceTest.rejectionDoesNotGenerateMedCheckinReminders 随 review 流程移除 MedCheckinService 依赖而删除（保留即成空验证）。剩余未勾项：浏览器/支付宝开发者工具人工实测（三角色登录核对、处方药配送/自取、OTC 选药房、过期重下、现场补药、双终态与提醒）通过后方可置 done 并给 README T88 加 [x]。
- 药师角色评估结论：采用一个全局 `pharmacist` staff 账号，不建人员档案、不做院区绑定；主要改动是现有 blanket admin 鉴权拆成路由级角色矩阵、B 端菜单/落点和 seed，复杂度中等且纳入本票，不另拆票。
