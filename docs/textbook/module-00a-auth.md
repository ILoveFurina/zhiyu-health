# 基础模块A：登录与鉴权

## 业务概述

本模块是整套系统的安全底座：C 端支付宝小程序采用免注册 mock 登录，按昵称取或建患者身份并签发患者 JWT；B 端管理后台使用账号密码（BCrypt 校验）登录，签发员工 JWT；server-py Agent 层回调 server-java 的业务接口时改用共享密钥头认证。三类受众共用同一套 HMAC-SHA JWT 密钥与过滤器体系，由 server-java 唯一对外入口统一鉴权，B/C 端令牌通过 `scope` claim 严格隔离，不可混用。

## 业务流程

1. 小程序启动：`app.onLaunch` 调用 `config.js` 的探活逻辑选择本机/隧道 API 地址，同时 `auth.js` 的 `ensureLogin()` 开始检查本地缓存 token。
2. 缓存 token 按 JWT `exp` 判断有效性（留 30s 余量）；无效或未命中时，小程序向 server-java `POST /api/c/auth/mock-login` 提交昵称（演示固定为 seed 患者"林小满"）。
3. server-java `CAuthController` → `PatientService.mockLogin()` 按昵称查 `patients` 表，不存在则插入新患者，随后 `PatientTokenService.issue()` 签发 `scope=c_patient` 的 JWT（默认 12h）。
4. 小程序把 token 与患者信息写入 storage；之后所有业务请求经 `request.js` 先 `await ensureLogin()` 再携带 `Authorization: Bearer <token>` 发出。
5. server-java 过滤器链按 order 10（审计）→ 20（鉴权）→ 25（演示冻结）→ 30（限流）执行；`AuthFilter` 校验 JWT 签名与 `scope`（`/api/c/**` 要求 `c_patient`，`/api/b/**` 要求 `staff`），把 `authSubject`/`authRole` 写入请求 attribute。
6. B 端：管理员/医生在 React 登录页提交账号密码 → `POST /api/b/auth/login` → `AuthService.authenticate()` 用 BCrypt 校验口令，签发 `scope=staff` 且带 `role` claim 的 JWT。
7. admin 前端存 token 后拉取 `/api/b/auth/me` 得到角色，按 `homeByRole()` 整页跳转到角色落地页（admin → `/hospitals`，doctor → `/workbench`）；此后 `AdminInterceptor` 在服务端保证组织管理类接口仅 admin 可用。
8. 运行期任何请求返回 401：admin 端 `errorHandler` 清 token 并 `history.replace('/login')`；小程序端下次请求会因 token 失效重新走 mock 登录。
9. server-py 工具回调 server-java `/api/agent/**` 时不走 JWT，而是携带 `X-Agent-Callback-Token` 共享密钥头，由 `AgentCallbackAuthFilter` 用常量时间比较校验。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| 小程序 utils | 免注册登录、token 缓存与 exp 预判 | `miniprogram/utils/auth.js` |
| 小程序 utils | 统一请求封装，自动登录 + 携带 Bearer 头 | `miniprogram/utils/request.js` |
| 小程序 utils | API 地址运行时选择（localhost 探活） | `miniprogram/utils/config.js` |
| server-java controller | C 端 mock 登录端点 | `server-java/src/main/java/com/zhiyu/health/controller/patient/common/CAuthController.java` |
| server-java controller | B 端登录与当前员工资料 | `server-java/src/main/java/com/zhiyu/health/controller/staff/common/BAuthController.java` |
| server-java service | 患者取或建、患者 JWT 签发/校验 | `service/common/PatientService.java`、`service/common/PatientTokenService.java` |
| server-java service | B 端口令校验与员工 JWT 签发 | `server-java/src/main/java/com/zhiyu/health/service/common/AuthService.java` |
| server-java config | JWT 过滤器（scope 分端）、Agent 回调密钥过滤器、admin 角色拦截器、装配 | `config/AuthFilter.java`、`config/AgentCallbackAuthFilter.java`、`config/AdminInterceptor.java`、`config/WebConfig.java`、`config/JwtKeys.java`、`config/AuthConfig.java` |
| admin services/utils | 登录/me 接口封装、token 与当前用户缓存、角色落地页 | `admin/src/services/auth.ts`、`admin/src/utils/session.ts` |
| admin 运行时 | 401 跳登录、路由守卫、权限定义 | `admin/src/app.tsx`、`admin/src/access.ts` |
| admin 页面 | 登录表单与登录后跳转 | `admin/src/pages/Login/index.tsx` |

## 核心代码走读

### A.1 小程序免注册 mock 登录与共享登录 Promise

`miniprogram/utils/auth.js:53-86`：

```js
function ensureLogin() {
  if (loginPromise) return loginPromise
  const cached = getToken()
  // 过期令牌必须落到登录分支重取，否则所有请求带着过期令牌反复 401
  if (cached && isTokenUsable(cached)) {
    return Promise.resolve(cached)
  }
  loginPromise = new Promise((resolve, reject) => {
    my.request({
      url: `${apiBaseUrl}/c/auth/mock-login`,
      method: 'POST',
      // 本地演示账号：林小满（seed 患者 id=1，自带档案/过敏史/票 61 报告观测趋势）。
      data: { nickname: '林小满' },
      headers: { 'Content-Type': 'application/json' },
      success: (res) => {
        if (res.status === 200 && res.data && res.data.token) {
          my.setStorageSync({ key: 'token', data: res.data.token })
          my.setStorageSync({ key: 'patient', data: res.data.patient })
          resolve(res.data.token)
        } else {
          loginPromise = null
          reject(new Error(`登录失败（${res.status}）`))
        }
      },
```

三个教学点：其一，`loginPromise` 模块级单例解决了 `app.onLaunch` 异步登录与页面首请求并发时的竞态——所有请求共享同一次登录，token 落 storage 后业务请求才发出。其二，`isTokenUsable()`（`auth.js:10-19`）在端侧解析 JWT payload 按 `exp` 留 30s 余量预判过期，避免带过期令牌反复 401；小程序运行时没有 `atob/Buffer`，`base64UrlDecode`（`auth.js:23-43`）是逐字节手写的。其三，mock 登录是演示边界：不传任何凭证，后端按昵称"取或建"患者。

服务端承接在 `server-java/src/main/java/com/zhiyu/health/service/common/PatientService.java:20-29`：

```java
@Transactional
public Patient mockLogin(String nickname) {
    String resolved = nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname.trim();
    Patient patient = patientMapper.selectOne(new LambdaQueryWrapper<Patient>().eq(Patient::getNickname, resolved));
    if (patient != null) {
        return patient;
    }
    Patient created = new Patient(null, resolved);
    patientMapper.insert(created);
    return created;
}
```

`CAuthController.java:29-34` 只做装配：调 `mockLogin` 后用 `PatientTokenService.issue()` 签发令牌返回 `{token, patient}`。

### A.2 一套 JWT 三种受众：scope 分端与回调密钥

三种令牌的区分全部体现在 claim 与过滤器上。患者令牌由 `server-java/src/main/java/com/zhiyu/health/service/common/PatientTokenService.java:29-38` 签发：

```java
public String issue(Long patientId) {
    Instant now = Instant.now();
    return Jwts.builder()
            .subject(patientId.toString())
            .claim("scope", SCOPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(expireMinutes, ChronoUnit.MINUTES)))
            .signWith(key)
            .compact();
}
```

员工令牌由 `AuthService.java:46-55` 签发，`scope=staff` 并额外携带 `role` claim 供 B 端鉴权：

```java
public String createAccessToken(StaffUser staff) {
    Instant expiresAt = Instant.now().plus(tokenTtl);
    return Jwts.builder()
            .subject(String.valueOf(staff.getId()))
            .claim("scope", "staff")
            .claim("role", staff.getRole())
            .expiration(Date.from(expiresAt))
            .signWith(jwtKey)
            .compact();
}
```

两端的密钥派生必须一致，这是 `JwtKeys.java:12-14` 存在的唯一理由（`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`）。分端隔离在 `AuthFilter.java:68-76`：

```java
String path = request.getRequestURI();
String requiredScope = path.startsWith("/api/c/") ? "c_patient" : "staff";
if (!requiredScope.equals(claims.get("scope", String.class))) {
    reject(response);
    return;
}

request.setAttribute(ATTR_AUTH_SUBJECT, claims.getSubject());
request.setAttribute(ATTR_AUTH_ROLE, claims.get("role", String.class));
```

即患者 token 无法访问 `/api/b/**`，员工 token 也无法访问 `/api/c/**`。第三种"受众"是 server-py：`AgentCallbackAuthFilter.java:30-37` 保护 `/api/agent/**`，不用 JWT 而是常量时间比较共享密钥头，防伪造运行时上下文：

```java
String supplied = request.getHeader(HEADER_NAME);
boolean valid = supplied != null
        && MessageDigest.isEqual(expectedCredential, supplied.getBytes(StandardCharsets.UTF_8));
if (!valid) {
    ApiErrorBody.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Agent 回调认证失败");
    return;
}
```

### A.3 AuthFilter 的放行清单与模拟器引号坑

`server-java/src/main/java/com/zhiyu/health/config/AuthFilter.java:32-49`：

```java
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    // 登录端点本身不持令牌，放行
    if (path.startsWith("/api/c/auth/") || path.startsWith("/api/b/auth/login")) {
        return true;
    }
    // WebSocket upgrade 可能被 cpolar 等隧道重建并剥离 Authorization；只放行 HTTP
    // 握手，患者 JWT 必须在连接后的首个 auth 信封中校验，未认证会话不能发送 chat。
    if ("/api/c/chat/ws".equals(path)) {
        return true;
    }
    // 图片代理端点放行：支付宝 <image src> 组件不带 Authorization header，
    // 以 object_key 的 UUID 不可猜测性作为取图凭证（ADR-0023 demo 场景）。
    if (path.startsWith("/api/c/photos")) {
        return true;
    }
    return !path.startsWith("/api/c/") && !path.startsWith("/api/b/");
}
```

放行项每一条都对应一个真实工程约束：登录端点必然无令牌；聊天 WS 握手会被隧道剥头，鉴权后移到连接内 `auth` 首帧（用 `PatientTokenService.verify()` 复用同一密钥与 scope 纪律）；图片 `<image src>` 无法带 header，以 UUID object_key 做不可猜测凭证。另外 `AuthFilter.java:88-93` 的 `stripSimulatorQuotes` 专门剥掉支付宝开发者工具给 header 值包上的字面双引号，对标准客户端是 no-op。

### A.4 过滤器装配顺序与 admin 角色拦截

`server-java/src/main/java/com/zhiyu/health/config/WebConfig.java:53-68` 把鉴权固定在 order 20：

```java
@Bean
public FilterRegistrationBean<AuthFilter> authFilterRegistration() {
    FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(jwtSecret));
    bean.addUrlPatterns("/api/*");
    bean.setOrder(20);
    return bean;
}

@Bean
public FilterRegistrationBean<AgentCallbackAuthFilter> agentCallbackAuthFilterRegistration() {
    FilterRegistrationBean<AgentCallbackAuthFilter> bean =
            new FilterRegistrationBean<>(new AgentCallbackAuthFilter(agentCallbackSecret));
    bean.addUrlPatterns("/api/agent/*");
    bean.setOrder(20);
    return bean;
}
```

整体顺序是审计 10 → 鉴权 20 → 演示冻结 25 → 限流 30，鉴权先于限流是为了让限流按已认证 subject 计键。注意 `AuthFilter` 不注册为 `@Component`，只经 `FilterRegistrationBean` 显式装配，避免被 Servlet 容器与 Spring 双重注册。

角色级控制不靠过滤器，而是 `WebConfig.java:89-93` 的 MVC 拦截器 + `AdminInterceptor.java:15-20`：

```java
registry.addInterceptor(new AdminInterceptor())
        .addPathPatterns("/api/b/**")
        .excludePathPatterns("/api/b/auth/**", "/api/b/reception/**");
```

```java
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (StaffUser.ROLE_ADMIN.equals(request.getAttribute(AuthFilter.ATTR_AUTH_ROLE))) {
        return true;
    }
    throw new ApiException(403, "仅管理员可操作");
}
```

它直接消费 `AuthFilter` 写入的 `authRole` attribute：`/api/b/**` 默认仅 admin，放行认证区与接诊台（doctor 的接诊权限由 `ReceptionService` 在业务层另行校验）。这体现了"过滤器管身份、拦截器管粗粒度角色、service 管细粒度业务权限"的三层分工。

### A.5 B 端登录、401 跳登录与角色落地页

服务端 `BAuthController.java:33-49`：

```java
@PostMapping("/login")
public TokenResponse login(@Validated @RequestBody LoginRequest request) {
    StaffUser staff = authService.authenticate(request.username(), request.password());
    if (staff == null) {
        throw new ApiException(401, "账号或密码错误");
    }
    return new TokenResponse(authService.createAccessToken(staff), "bearer");
}
```

前端登录提交在 `admin/src/pages/Login/index.tsx:165-176`：

```tsx
onFinish={async (values) => {
  const { access_token } = await login(
    values as { username: string; password: string },
  );
  setToken(access_token);
  const currentUser = await fetchMe();
  setCachedUser(currentUser);
  await setInitialState({ currentUser });
  // access 插件在同一轮单页跳转中仍可能读取旧权限；完整 replace 后由
  // getInitialState 重新拉取 /me，避免医生首次登录短暂落入 403 页面。
  window.location.replace(homeByRole(currentUser.role));
}}
```

角色落地规则在 `admin/src/utils/session.ts:28-30`：`admin → /hospitals`，其余（doctor）→ `/workbench`。用 `window.location.replace` 整页跳转而非 SPA 路由，是为了让 Umi access 插件基于新的 `/me` 结果重建权限，避免医生首次登录短暂看到 403。

运行期 401 与路由守卫在 `admin/src/app.tsx:78-109`：

```tsx
errorConfig: {
  errorHandler: (error: any) => {
    const { response } = error;
    if (response?.status === 401 && getToken() && history.location.pathname !== LOGIN_PATH) {
      clearToken();
      history.replace(LOGIN_PATH);
    }
```

```tsx
export function onRouteChange({ location }: { location: { pathname: string } }) {
  const { pathname } = location;
  const loggedIn = !!getToken();
  if (!loggedIn) {
    if (pathname !== LOGIN_PATH) history.replace(LOGIN_PATH);
    return;
  }
  if (pathname === LOGIN_PATH || pathname === '/') {
    void resolveUser().then((user) => {
      history.replace(user ? homeByRole(user.role) : LOGIN_PATH);
    });
    return;
  }
  if (getCachedUser()?.role !== 'admin' && ADMIN_PATHS.some((p) => pathname.startsWith(p))) {
    history.replace('/workbench');
  }
}
```

前端守卫只是体验层兜底——真正的权限裁决在服务端的 `AuthFilter` + `AdminInterceptor`。`access.ts` 则把 `role` 映射为 `canAdmin/canDoctor` 两个权限点供菜单与按钮级控制使用。

## 契约与 ADR

- 本模块无独立 `contracts/*.json` 契约文件；登录/鉴权属栈内实现细节，跨栈契约（状态机、消息类型、SSE 事件）的单一事实源机制本身由 ADR-0010《跨栈契约：contracts/ JSON 单一事实源 + 双栈启动加载》定义（注意与 ADR-0010《RAG 知识检索只用于受控证据问答与技术演示》区分，两者同号不同题）。
- ADR-0009《响应赛题约束的技术栈调整：B 端 React 化与后端双栈拆分》：确立 server-java 是唯一对外入口，三种受众的鉴权都收口在它的过滤器链。
- ADR-0008（支付宝原生小程序）：C 端无注册页、mock 登录的端形态前提。
- ADR-0012《药品订单与挂号收费：实体真实、支付 Mock》：与 mock 登录同属"实体真实、凭证 Mock"的演示边界策略。
- ADR-0023（拍照分析图片持久化 MinIO）：`AuthFilter` 放行 `/api/c/photos` 的依据——`<image src>` 不带凭证，以 UUID object_key 不可猜测性兜底。

## 讲解提示

- 强调"一套 JWT 三种受众"的关键不在密钥（同一把 HMAC 密钥）而在 `scope` claim + 过滤器按路径前缀强制匹配；可现场演示拿患者 token 调 `/api/b/auth/me` 必 401。
- 学生常问"mock 登录安全吗"：答案要点是它是显式的演示边界（Mock 边界注释），真实环境应替换为支付宝授权码换登录态；但即使如此，签发后的 JWT 校验链路（过滤器、scope、exp）与生产形态一致，是教学重点。
- 学生常问"为什么 B 端鉴权既有 Filter 又有 Interceptor"：Filter 解决"是不是合法员工/患者"（身份），Interceptor 解决"这个角色能不能进这族接口"（粗粒度授权），细粒度业务权限（如医生只能操作自己的接诊）下沉到 service 层；三层各管一段，避免一个组件膨胀。
- 前端 401 跳转与路由守卫只是体验优化，安全裁决永远以 server-java 为准；可让学生注释掉 `onRouteChange` 观察直接访问 `/hospitals` 时后端 401/403 的兜底行为。

> 返回目录：[docs/textbook/README.md](./README.md)
