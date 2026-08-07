# 57 - 15 位医生全部可登录（staff_users 补种）

**What to build:** `doctors` 表有 15 位医生（seed.sql id 1-15），但 `staff_users` 只 seed 了 `admin`、`doctor.lin`（doctorId=1）、`doctor.zhou`（doctorId=2）三个账号，其余 13 位医生（id 3-15）无账号，无法登录 B 端接诊台进行接诊测试。本票扩展 `StaffUserSeed`，按"姓拼音"用户名约定为 id 3-15 补种账号，密码由新 env 变量 `SEED_DOCTORS_PASSWORD` 注入（缺省 `doctor123456`），与现有 `seedIfAbsent` 幂等模式一致。

**Blocked by:** 无

**Status:** claimed

**账号清单（用户名沿用 doctor.<姓拼音> 约定）**

| doctorId | 医生 | 用户名 |
|---|---|---|
| 3 | 陈清禾 | doctor.chen |
| 4 | 苏明哲 | doctor.su |
| 5 | 李婉清 | doctor.li |
| 6 | 赵启明 | doctor.zhao |
| 7 | 吴佩珊 | doctor.wu |
| 8 | 孙立航 | doctor.sun |
| 9 | 郑雅文 | doctor.zheng |
| 10 | 马俊杰 | doctor.ma |
| 11 | 何静怡 | doctor.he |
| 12 | 黄志远 | doctor.huang |
| 13 | 梁书瑶 | doctor.liang |
| 14 | 冯雪松 | doctor.feng |
| 15 | 韩思敏 | doctor.han |

**改动范围**
- [x] `StaffUserSeed.java`：新增 doctorId→用户名映射表（13 条），循环 `seedIfAbsent`（ROLE_DOCTOR + doctorId 绑定，密码从 `zhiyu.seed.doctors-password` 注入）；`admin/doctor.lin/doctor.zhou` 三个既有账号逻辑不动
- [x] `application.yml`：`zhiyu.seed.doctors-password: ${SEED_DOCTORS_PASSWORD:doctor123456}`
- [x] `.env.example`：补 `SEED_DOCTORS_PASSWORD` 注释行
- [x] `AGENTS.md`：演示账号段落更新（SEED_* 变更同步约定）
- [x] `.scratch/zhiyu-mvp/demo-script.md`：演示账号表补 13 个账号（统一密码 doctor123456）
- [x] 新增 `StaffUserSeedTest` 单测（mock mapper）：15 个账号全部 seed、已存在跳过、未配置密码跳过；现有 `BAuthControllerTest` 等 mock 了 AuthService，不受影响

**验证**
- [x] `mvn -f server-java/pom.xml spotless:check` 通过
- [x] `mvn -f server-java/pom.xml test` 通过（受影响模块 + 契约测试：StaffUserSeedTest 4、BAuthControllerTest 6、ContractsTest 21、AuthServiceTest 4）
- [ ] 重启本地 server-java（直连云演示库 zhiyu），确认 15 个账号幂等补种且密码可用（`doctor.chen/doctor123456` 登录接诊台）；不需要跑 `reset_zhiyu.py`

## Comments
