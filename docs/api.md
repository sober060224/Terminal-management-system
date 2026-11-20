# 轮渡码头客运管理系统 API 文档

**版本**: M0 (2026-11-20)  
**基础路径**: `/api`  
**认证方式**: Session Cookie (Spring Security)

---

## 1. 统一响应体

所有接口返回 JSON 格式，结构如下：

```json
{
  "success": true,
  "message": "操作成功",
  "data": { ... },
  "code": null
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | boolean | 操作是否成功 |
| `message` | string | 操作消息 |
| `data` | object/array/null | 返回数据（失败时为 null） |
| `code` | string/null | 错误码；成功时为 null，失败时取值见第 2 节 |

前端按 `code` 分支处理，不要解析 `message` 文案。

### HTTP 状态码约定

| 状态码 | 含义 | 响应体 `success` |
|--------|------|------------------|
| 200 | 成功 | true |
| 400 | 请求参数错误 | false |
| 401 | 未登录或认证失败 | false |
| 403 | 无权限访问；鉴权失败的 `message` 为「当前角色没有执行该操作的权限」 | false |
| 404 | 资源不存在 | false |
| 409 | 业务冲突（如用户名已存在） | false |
| 500 | 服务器内部错误 | false |

---

## 2. 错误码表（M0）

| 错误码 | HTTP 状态码 | message 示例 |
|--------|-------------|--------------|
| `AUTH_FAILED` | 401 | "用户名或密码错误" |
| `NOT_LOGGED_IN` | 401 | "请先登录" |
| `FORBIDDEN` | 403 | "当前角色没有执行该操作的权限" |
| `USERNAME_EXISTS` | 409 | "用户名已存在" |
| `VALIDATION_ERROR` | 400 | "手机号格式不正确" |
| `NOT_FOUND` | 404 | "资源不存在" |
| `CONFLICT` | 409 | "数据冲突，请检查输入" |
| `SERVER_ERROR` | 500 | "服务器内部错误，请稍后重试" |

后端常量定义在 `backend/src/main/java/com/gdou/ferry/common/ErrorCodes.java`。

失败响应示例：

```json
{
  "success": false,
  "message": "用户名已存在",
  "data": null,
  "code": "USERNAME_EXISTS"
}
```

---

## 3. 认证接口（M0 全部接口）

M0 只有下面 5 个接口。业务接口（航线/班次/售票/检票/统计）从 M1 起按模块往上加，
它们的路径前缀与所需角色已经是确定的：

| 路径前缀 | 允许的角色 | M0 现状 |
|----------|------------|---------|
| `/api/auth/**` | 公开（登录/注册/取令牌）或已登录（current/logout） | 已实现 |
| `/api/admin/**` | 仅 `ADMIN` | 尚未实现任何业务接口，管理员访问会得到 **404**，其他角色得到 **403** |
| `/api/seller/**` | `SELLER` 与 `ADMIN` | 同上 |
| 其他 `/api/**` | 任何已登录用户 | 同上 |

判定规则（`backend/.../config/SecurityConfig.java`）：先按角色过滤（不满足 → 403
`FORBIDDEN`），再落到控制器（没有对应处理方法 → 404）。所以「管理员 404 / 售票员 403」
这一对结果就是角色过滤生效的证据，不能只看「不是 403」。


### 3.0 获取 CSRF Token

**请求**

```http
GET /api/auth/csrf
```

**成功响应** (200)

```json
{
  "success": true,
  "message": "操作成功",
  "data": {
    "token": "6aaf0b8d-1c2e-4f3a-9b7d-2e5c8a41f0d3",
    "ready": true
  },
  "code": null
}
```

**作用**：Spring Security 的 CSRF 令牌是延迟加载的。客户端不先取一次，Cookie 里就不会有
`XSRF-TOKEN`，随后任何 POST 都会被 CSRF 过滤器拦成 403（`FORBIDDEN`）。

前端首屏或首次写操作前调用一次即可；后续 axios 会自动带上
`X-XSRF-TOKEN` 请求头。

---

### 3.1 用户注册

**请求**

```http
POST /api/auth/register
Content-Type: application/json
```

```json
{
  "username": "passenger1",
  "password": "123456",
  "name": "王乘客",
  "phone": "13800138000",
  "idCard": "440100199004041004"
}
```

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | string | 是 | 登录账号，唯一，3-50字符 |
| `password` | string | 是 | 密码，6-50字符 |
| `name` | string | 是 | 真实姓名 |
| `phone` | string | 是 | 手机号，11位数字 |
| `idCard` | string | 是 | 身份证号，18位 |

**成功响应** (200)

```json
{
  "success": true,
  "message": "注册成功",
  "data": {
    "id": 6,
    "username": "passenger1",
    "role": "PASSENGER",
    "name": "王乘客",
    "phone": "13800138000",
    "status": "ACTIVE",
    "createTime": "2026-11-20T21:18:25"
  },
  "code": null
}
```

**失败响应**

- 用户名已存在 (409):
```json
{
  "success": false,
  "message": "用户名已存在",
  "data": null,
  "code": "USERNAME_EXISTS"
}
```

- 参数校验失败 (400):
```json
{
  "success": false,
  "message": "手机号格式不正确",
  "data": null,
  "code": "VALIDATION_ERROR"
}
```

---

### 3.2 用户登录

**请求**

```http
POST /api/auth/login
Content-Type: application/json
```

```json
{
  "username": "admin",
  "password": "admin123"
}
```

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | string | 是 | 登录账号 |
| `password` | string | 是 | 密码 |

**成功响应** (200)

```json
{
  "success": true,
  "message": "登录成功",
  "data": {
    "id": 1,
    "username": "admin",
    "role": "ADMIN",
    "name": "系统管理员",
    "phone": "13900139000",
    "status": "ACTIVE",
    "createTime": "2026-11-20T21:18:25"
  },
  "code": null
}
```

响应头会设置 `Set-Cookie: JSESSIONID=...; Path=/; HttpOnly; SameSite=Lax`

**失败响应**

- 用户名或密码错误 (401):
```json
{
  "success": false,
  "message": "用户名或密码错误",
  "data": null,
  "code": "AUTH_FAILED"
}
```

- 账号已禁用 (403):
```json
{
  "success": false,
  "message": "账号已被禁用",
  "data": null,
  "code": "FORBIDDEN"
}
```

---

### 3.3 获取当前登录用户信息

**请求**

```http
GET /api/auth/current
Cookie: JSESSIONID=...
```

**成功响应** (200)

```json
{
  "success": true,
  "message": "操作成功",
  "data": {
    "id": 1,
    "username": "admin",
    "role": "ADMIN",
    "name": "系统管理员",
    "phone": "13900139000",
    "status": "ACTIVE",
    "createTime": "2026-11-20T21:18:25"
  },
  "code": null
}
```

**失败响应**

- 未登录 (401):
```json
{
  "success": false,
  "message": "请先登录",
  "data": null,
  "code": "NOT_LOGGED_IN"
}
```

---

### 3.4 用户登出

**请求**

```http
POST /api/auth/logout
Cookie: JSESSIONID=...
```

**成功响应** (200)

```json
{
  "success": true,
  "message": "登出成功",
  "data": null,
  "code": null
}
```

响应会清除 session cookie。

---

## 4. 数据模型（M0 用户信息）

### UserDTO

用户信息返回对象，不含敏感字段。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | number | 用户ID |
| `username` | string | 登录账号 |
| `role` | string | 角色：`ADMIN` / `SELLER` / `PASSENGER` |
| `name` | string | 真实姓名 |
| `phone` | string | 手机号 |
| `status` | string | 状态：`ACTIVE` / `DISABLED` / `PENDING` |
| `createTime` | string | 注册时间，`yyyy-MM-ddTHH:mm:ss`（本地时区 `Asia/Shanghai`，秒级，无小数位） |

**注意**: `password` 和 `idCard` 永远不出现在响应中。

---

## 5. CSRF 防护

- Spring Security 默认启用 CSRF 保护
- **先调 `GET /api/auth/csrf`**：该调用负责把 `XSRF-TOKEN` Cookie 种到浏览器上（Cookie 非 HttpOnly，前端 JS 可读）
- 前端从 Cookie `XSRF-TOKEN` 读取 token，在请求头 `X-XSRF-TOKEN` 中回传
- 所有 POST/PUT/DELETE 请求必须携带 CSRF token
- GET 请求不需要 CSRF token

**示例**（axios 自动处理）:

```javascript
axios.defaults.withCredentials = true;
axios.defaults.xsrfCookieName = 'XSRF-TOKEN';
axios.defaults.xsrfHeaderName = 'X-XSRF-TOKEN';
```

### 5.1 令牌生命周期（登出会让令牌失效）

`POST /api/auth/logout` 会 `deleteCookies(JSESSIONID, XSRF-TOKEN)`，响应里不再有新的令牌。
因此**登出后不能再复用之前取到的令牌**：同一个 SPA 里「退出登录 → 换账号登录」时，前端
必须先重新 `GET /api/auth/csrf`，否则登录请求没有 `X-XSRF-TOKEN` 头，后端会回 403
`FORBIDDEN`（而不是 401 —— 这属于 CSRF 校验失败，不是认证失败）。

不需要手动判断场景：网络层只要保证「写操作前 Cookie 里没有 `XSRF-TOKEN` 就先取一次」即可，
前端实现见 `frontend/src/api/request.js` 的请求拦截器（只在请求进行中复用，完成后即作废）。

---

## 6. 前端路由约定（M0）

登录成功后，根据角色跳转：

| 角色 | 跳转路径 | 说明 |
|------|----------|------|
| `ADMIN` | `/admin` | 管理后台首页 |
| `SELLER` | `/seller` | 售票员工作台 |
| `PASSENGER` | `/` | 乘客端首页 |

未登录访问受保护路由时，重定向到 `/login?redirect=原路径`。

---

## 附录：测试账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | ADMIN |
| seller1 | seller123 | SELLER |
| passenger1 | 123456 | PASSENGER |

密码均为 BCrypt 哈希存储，种子哈希见 `database.sql` 末尾注释。
