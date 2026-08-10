# 模块10：组织管理（B 端 CRUD 样板）

## 业务概述

组织管理是 B 端管理后台的基础数据维护模块，覆盖医院、院区、科室分类、平台标准科室、实际科室、医生六类档案的增删改查。它是全项目 B 端 CRUD 的标准样板：server-java 侧统一「Controller 只做校验与装配 + Service 继承 MyBatis-Plus `ServiceImpl` + MapStruct 做 DTO 映射」，前端统一「`services/organization.ts` 集中 API + ProTable 列表 + ModalForm 弹窗表单」。医生档案额外带一条 MinIO 头像旁路上传链路。读懂这一个模块，其余 B 端 CRUD 页面即可举一反三。

## 业务流程

1. 管理员在 B 端登录后进入「医院管理」等页面，前端 `ProTable` 的 `request` 调用 `admin/src/services/organization.ts` 中的 `listHospitals()` 拉取全量列表。
2. 点「新建/编辑」打开 `ModalForm` 弹窗，填写后提交，前端调用 `createXxx` / `updateXxx`（POST/PUT）。
3. server-java 的 `AdminInterceptor` 在 `/api/b/**` 上校验 admin 角色；Controller 用 Jakarta Validation 注解校验入参 record，再用 MapStruct 映射器转成 Entity。
4. Service（继承 `ServiceImpl`）执行业务校验：外键存在性（404）、删除前的子数据限制（409），然后调用 `save` / `updateById` / `removeById` 写 PostgreSQL。
5. 删除走 `DELETE /api/b/xxx/{id}`，service 层先做「全链限制删除」检查（如医院下有院区、医生有排班则 409 拒绝），通过后才删。
6. 医生照片为可选旁路：前端 `Upload` 组件先 `POST /api/b/doctors/photos` 上传文件，server-java 校验类型/大小后写 MinIO，返回 `object_key` 存入 `doctors.photo_url`；回显时走鉴权代理 `GET /api/b/photos?key=...` 拉 blob 渲染，bucket 不开公共读。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| admin service | 六类档案的 CRUD API 函数与 TS 类型 | `admin/src/services/organization.ts` |
| admin 页面 | 医院列表页（ProTable 样板） | `admin/src/pages/Hospital/index.tsx`、`components/HospitalForm.tsx` |
| admin 页面 | 医生列表页 + 表单 + 头像 | `admin/src/pages/Doctor/index.tsx`、`components/DoctorForm.tsx`、`components/DoctorPhoto.tsx` |
| admin 契约 | 从 contracts JSON 推导的上传限制与响应类型 | `admin/src/contracts/doctorPhoto.ts` |
| server-java controller | 六类档案的 REST 入口，校验与装配 | `server-java/src/main/java/com/zhiyu/health/controller/staff/organization/*Controller.java` |
| server-java mapping | Input record → Entity 的 MapStruct 映射器 | `server-java/.../controller/staff/organization/mapping/*InputMapper.java` |
| server-java service | CRUD 业务逻辑，继承 `ServiceImpl`，含删除限制 | `server-java/.../service/organization/*AdminService.java` |
| server-java entity | 镜像 schema.sql 的表实体 | `server-java/.../entity/organization/*.java` |
| server-java 照片 | 医生照片上传/回拉代理 | `server-java/.../controller/staff/organization/DoctorPhotoController.java` |
| 契约事实源 | 医生头像上传限制与响应结构 | `contracts/doctor-photo-limits.json` |

## 核心代码走读

### 10.1 前端 service 层：一个文件管六类档案

`admin/src/services/organization.ts:61-75`：

```ts
export function listHospitals() {
  return request<Hospital[]>('/api/b/hospitals');
}

export function createHospital(body: Omit<Hospital, 'id'>) {
  return request<Hospital>('/api/b/hospitals', { method: 'POST', data: body });
}

export function updateHospital(id: number, body: Omit<Hospital, 'id'>) {
  return request<Hospital>(`/api/b/hospitals/${id}`, { method: 'PUT', data: body });
}

export function removeHospital(id: number) {
  return request(`/api/b/hospitals/${id}`, { method: 'DELETE' });
}
```

所有六类档案的 API 函数集中在同一个文件，模式完全一致：列表 GET、新建 POST、更新 PUT、删除 DELETE，统一走 Umi 的 `request`（自动带 token 与错误处理）。文件顶部为每类档案手写 TS 接口（如 `Hospital` 在 `organization.ts:5-9`），字段用 snake_case 与后端 JSON 对齐；写操作统一用 `Omit<Hospital, 'id'>` 表示「提交体不含 id」。教学上注意：这是全项目前端 service 层的标准形态，其余模块（排班、问诊、订单）都照此布局。

### 10.2 列表页范式：ProTable + 本地过滤 + ModalForm

`admin/src/pages/Hospital/index.tsx:102-109`：

```tsx
        dataSource={filtered}
        request={async () => {
          const data = await listHospitals();
          setAll(data);
          return { data, success: true };
        }}
      />
      <HospitalForm open={open} record={record} onOpenChange={setOpen} onSuccess={reload} />
```

列表页骨架为：`ProTable` 的 `request` 一次性拉全量存入 `all`，搜索框/下拉在本地即时过滤（`index.tsx:28` 的 `filtered`），不走服务端分页查询；页头 `StatCards` 统计卡直接从已加载数据实时计算。编辑与新建共用同一个弹窗组件：`record` 有值即编辑回显，`undefined` 即新建；删除用 `Popconfirm` 二次确认（`index.tsx:48-51`），成功后 `actionRef.current?.reload()` 刷新。医生页（`Doctor/index.tsx`）在此骨架上加了院区→科室联动过滤、年龄实时计算（`ageOf`，`Doctor/index.tsx:21-30`）和照片列。

### 10.3 Controller：只做校验与装配

`server-java/src/main/java/com/zhiyu/health/controller/staff/organization/DoctorController.java:38-58`：

```java
    public record DoctorInput(
            @NotNull Long departmentId,
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 10) String gender,
            @NotNull java.time.LocalDate birthDate,
            @NotBlank @Size(max = 50) String title,
            @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") BigDecimal registrationFee,
            @NotBlank String specialty,
            // 照片可选（无图留空）；非空时必须是 MinIO object key（票 54：禁止任意 URL 入库）
            @Size(max = 500) String photoUrl) {}

    @GetMapping
    public List<Doctor> list() {
        return doctorAdminService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Doctor create(@Validated @RequestBody DoctorInput input) {
        validatePhotoUrl(input.photoUrl());
        return doctorAdminService.create(doctorInputMapper.toEntity(input));
    }
```

这是全项目 controller 的标准写法，体现三条硬约定：入参定义为带 Jakarta Validation 注解的 `record`，格式校验交给 `@Validated`，controller 零 try-catch；方法体只有「校验 → 映射 → 调 service」三步，不含任何 SQL 或业务判断；`photoUrl` 这类特殊约束（必须是 MinIO object key 而非任意 URL）收敛到私有方法 `validatePhotoUrl`（`DoctorController.java:76-83`），不合法抛 `ApiException` 由统一 advice 出口。鉴权不在方法里写——`/api/b/**` 由 `AdminInterceptor` 统一限定 admin 角色。

### 10.4 MapStruct：DTO → Entity 一行接口

`server-java/src/main/java/com/zhiyu/health/controller/staff/organization/mapping/DoctorInputMapper.java:8-13`：

```java
/** DoctorInput → Doctor：id 由 controller 按 create/update 语义自行设置。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DoctorInputMapper {

    Doctor toEntity(DoctorController.DoctorInput input);
}
```

项目约定 DTO/Entity 映射全部用 MapStruct，禁止手写 setter 拷贝。`componentModel = "spring"` 让映射器成为可注入的 Bean；record 的 camelCase 字段（`departmentId`）与 Entity（`Doctor.java` 中同样 camelCase、由 MyBatis-Plus `@TableName("doctors")` 映射到 snake_case 列）按名字自动对齐；`unmappedTargetPolicy = IGNORE` 允许 `id` 无源字段——create 时留空由数据库自增，update 时由 controller 显式 `setId`（`DoctorController.java:65`）。六个档案各有一个对应的 `XxxInputMapper`，内容形态完全相同。

### 10.5 Service：ServiceImpl 骨架 + 全链限制删除

`server-java/src/main/java/com/zhiyu/health/service/organization/DoctorAdminService.java:44-55`：

```java
    public void delete(long doctorId) {
        if (getById(doctorId) == null) {
            throw new ApiException(404, "医生不存在");
        }
        // 全链限制删除（票 49）：医生存在排班即拒绝删除，避免孤儿排班/挂号与 PG FK 裸错。
        Long schedules =
                scheduleMapper.selectCount(Wrappers.<Schedule>lambdaQuery().eq(Schedule::getDoctorId, doctorId));
        if (schedules != null && schedules > 0) {
            throw new ApiException(409, "医生存在排班，无法删除");
        }
        removeById(doctorId);
    }
```

六个 AdminService 全部继承 MyBatis-Plus `ServiceImpl<Mapper, Entity>`，`save` / `updateById` / `removeById` / `getById` 直接可用，service 只写业务规则：写入前校验外键存在（`create`/`update` 中查 `departmentMapper.selectById`，缺失抛 404，见 `DoctorAdminService.java:28-42`）；删除前做「全链限制删除」——先查子表计数，存在子数据即抛 409 拒绝，避免孤儿数据和数据库 FK 裸错。`listAll` 统一 `orderByAsc("id")` 保证列表稳定。

同类模块差异速查（范式相同，只列差异点）：

| 模块 | 页面/表单差异 | service 差异 |
| --- | --- | --- |
| Hospital | 等级列用 `LevelTag` 渲染；统计卡并行拉科室/医生总数 | 删除限制：医院下有院区 → 409（`HospitalAdminService.java:45-49`） |
| Campus | 表单字段最多（城市编码、经纬度、楼层、材料、须知） | 写入校验医院外键；删除限制：院区下有科室 → 409（`CampusAdminService.java:48-52`） |
| DepartmentCategory | 仅院区 + 名称 + 排序 | 写入校验院区外键；删除限制：分类下有科室 → 409（`DepartmentCategoryAdminService.java:48-52`） |
| StandardDepartment | 平台级字典，带 `category` 分组；`listAll` 按 `category, sort_order, id` 排序（`StandardDepartmentAdminService.java:23`） | 无外键校验；删除限制：已被实际科室映射 → 409 |
| Department | 三层外键（院区/分类/标准科室），表单级联选择 | 写入经 `validateReferences` 校验三个外键；删除限制：科室下有医生 → 409（`DepartmentAdminService.java:53-58`） |
| Doctor | 唯一带照片上传；挂号费用 `ProFormDigit` | 写入校验科室外键；删除限制：医生有排班 → 409 |

### 10.6 医生照片：契约推导的 MinIO 旁路上传

前端上传前的类型/大小校验不硬编码，而是从契约 JSON 推导（`admin/src/contracts/doctorPhoto.ts:1-6`）：

```ts
import limits from '../../../contracts/doctor-photo-limits.json';

// 票 54：医生头像上传限制与响应结构，从 contracts/doctor-photo-limits.json 推导
export const doctorPhotoMaxBytes = limits.max_bytes;
export const doctorPhotoAllowedTypes = limits.allowed_types as readonly string[];
export const doctorPhotoMaxFiles = limits.max_files;
```

`admin/src/pages/Doctor/components/DoctorForm.tsx:64-74` 用这些常量拦截非法文件：

```tsx
  const beforeUpload = (file: File): boolean => {
    if (!doctorPhotoAllowedTypes.includes(file.type)) {
      message.error('照片仅支持 JPEG/PNG 格式');
      return false;
    }
    if (file.size > doctorPhotoMaxBytes) {
      message.error('照片不能超过 2MB');
      return false;
    }
    return true;
  };
```

服务端用同一事实源再校验一次（`DoctorPhotoController.java:84-91`，经 `Contracts.java:322` 的 `DoctorPhotoLimits` record 从 `contracts/doctor-photo-limits.json` 加载），随后走旁路持久化（`DoctorPhotoController.java:49-56`）：

```java
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        validate(file);
        Optional<String> objectKey = minioStorage.storePhoto(file);
        // 旁路降级：MinIO 不可用/写入失败时返回空 key，前端据此不写入 photo_url，不阻塞档案保存。
        String key = objectKey.orElse("");
        String url = key.isEmpty() ? "" : "/api/b/photos?key=" + key;
        return Map.of("object_key", key, "url", url);
    }
```

注意三个设计点：一是「旁路降级」——MinIO 不可用时返回空 key，前端收到空 key 只提示不阻塞，档案仍可保存（`DoctorForm.tsx:85-89`）；二是入库的是 object key 不是 URL，`doctors.photo_url` 存 `photos/2026-08-07/abc.jpg` 这类键；三是回显不开 bucket 公共读，`DoctorPhoto.tsx:29-31` 用 `fetch` 带 Bearer 头经 `GET /api/b/photos` 代理拉 blob 再 `createObjectURL` 渲染（因为 `<img>` 无法带鉴权头）。

## 契约与 ADR

- `contracts/doctor-photo-limits.json`：医生头像上传限制（单张 JPEG/PNG、≤2MB）与上传响应结构（`object_key`/`url`）的单一事实源，server-java 与 admin 双端共享，改动需双栈同步。
- ADR-0010《跨栈契约：contracts/ JSON 单一事实源 + 双栈启动加载》（`docs/adr/0010-cross-stack-contracts.md`）：解释为什么上传限制这类常量必须从 contracts/ 加载而非双端各自硬编码。注意目录里还有另一篇编号同为 0010 的《RAG 知识检索只用于受控证据问答与技术演示》（`docs/adr/0010-rag-knowledge-retrieval.md`），与本模块无关，引用时按标题区分。
- ADR-0023《拍照分析原图持久化：MinIO 对象存储 + messages image kind》（`docs/adr/0023-photo-analysis-image-persistence-minio.md`）：确立图片对象旁路存 MinIO、PostgreSQL 只存键的语义；票 54 把该语义扩展到医生头像。
- ADR-0029《在线问诊交流媒体消息：患者图片 + 语音输入，复用 AI 对话模块能力》（`docs/adr/0029-online-consultation-media-messages.md`）：同一条 MinIO 图片管道在问诊场景的复用，可对照理解「鉴权代理回拉」模式的一致性。

## 讲解提示

- 教学主线：用「医院」一条线贯穿 `organization.ts → Hospital/index.tsx → HospitalController → HospitalInputMapper → HospitalAdminService`，让学生描出六层调用链；随后只给差异表，让学生自己照范式「写」出 Department 模块，检验举一反三。
- 常见提问：「为什么列表不分页、不过滤后端？」——演示库数据量小，全量拉取 + 本地即时过滤换来「输入即生效」的交互；这是 demo 取舍，不是通用最佳实践，教学时应明确点出。
- 常见提问：「404 和 409 为什么不写 `try-catch`？」——项目约定异常只抛 `ApiException`，由统一 advice 出口转 HTTP 响应；controller 保持零 try-catch，业务规则全部沉在 service。
- 常见提问：「照片为什么存 object key 而不是 URL？」——bucket 不开公共读，URL 无法直接访问；key 即取图凭证，回显必须经 server-java 鉴权代理（ADR-0023），同时契约束定禁止任意 URL 入库，防止外链图片绕过审计。

> 返回目录：[docs/textbook/README.md](./README.md)
