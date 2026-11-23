# 轮渡码头客运管理系统 API 文档

**版本**: M3 (2026-11-21)  
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
  "idCard": "440100199001010015"
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

## 7. 模块三：班次、座位与订单（M3）

> 口径约定（与 `GLOSSARY.md`、`docs/adr/0004` 一致）：
> - `ticket.refund_amount` = **实际退回给乘客的现金**。退票按档位算；改签补差价记 0，退差额记差额。
> - `orders.total_amount` = **该订单当前的实收/应收金额**。支付时写入票面合计，退票时递减实退额，改签时改为新票票面合计。
> - 部分退票、改签窗口与手续费基准班次这三处与需求文档字面有出入，登记在 `docs/deviations.md`。
> - 改签是**多退少补**：差价 = 新班次票价 − 原票票价，同一接口内一次完成。
> - M3 主链路维护 `待支付 / 已支付 / 已退票 / 已取消`；`已检票 / 已完成` 属于模块四，但「有效票」口径（判据、查重、余票）已把它们算进去。
> - 权限前缀：`/api/seller/**` 需要 SELLER 或 ADMIN；其余 `/api/**` 只需登录。

### 7.1 班次查询

#### 7.1.1 售票中班次列表

```http
GET /api/schedules
```

不带查询参数，只返回 `status='售票中'` 且 `depart_time` 晚于当前时间（未发船）的班次，按发船时间升序。

**成功响应** (200)

```json
{
  "success": true,
  "message": "操作成功",
  "data": [
    {
      "id": 10,
      "routeId": 1,
      "shipId": 1,
      "departTime": "2026-11-24T08:00:00",
      "arriveTime": "2026-11-24T10:00:00",
      "price": 100.00,
      "totalSeats": 32,
      "routeName": "广州-香港",
      "startPort": "广州",
      "endPort": "香港",
      "shipName": "海洋一号",
      "remainingSeats": 30
    }
  ],
  "code": null
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 班次 ID |
| `routeId` | number | 航线 ID，改签按它判断是否同航线 |
| `shipId` | number | 船 ID |
| `departTime` / `arriveTime` | string | ISO 本地时间（`Asia/Shanghai`） |
| `price` | number | 票价 |
| `totalSeats` | number | 总座位数 |
| `routeName` / `startPort` / `endPort` | string | 航线信息 |
| `shipName` | string | 船名 |
| `remainingSeats` | number | 余票 = `totalSeats` −（座位表已售 + 尚未到期的锁定），与 GLOSSARY「余票」同一口径 |

#### 7.1.2 班次详情

```http
GET /api/schedules/{id}
```

`data` 与 7.1.1 数组中的单个元素结构相同。班次不存在返回 404 `NOT_FOUND`（`班次不存在`）。

### 7.2 座位图

```http
GET /api/schedules/{id}/seats
```

**成功响应** (200)

```json
{
  "success": true,
  "message": "操作成功",
  "data": {
    "scheduleId": 10,
    "seats": [
      { "seatId": 301, "seatNo": "A1", "state": "可选" },
      { "seatId": 302, "seatNo": "A2", "state": "锁定" },
      { "seatId": 303, "seatNo": "A3", "state": "已售" }
    ]
  },
  "code": null
}
```

`state` 取值只有 `可选` / `锁定` / `已售`，按 ADR-0003 惰性判断：票级已售优先，其次 `seat.lock_until` 未过期算锁定，否则可选。

没有单独的锁座/解锁接口：座位在下单时随订单一起锁定，锁定截止时间就是订单的 `expireAt`。

### 7.3 订单（乘客）

#### 7.3.1 下单

```http
POST /api/orders
Content-Type: application/json

{
  "scheduleId": 10,
  "seats": [
    { "seatNo": "A1", "passengerName": "张三", "idCard": "440100199001010015", "phone": "13800138000" }
  ]
}
```

- `seats` 至少 1 项、最多 5 项；座位号不能重复；同一订单内乘船人身份证号不能重复。
- 每个座位必须有 `phone`，11 位手机号，匹配 `^1[3-9]\d{9}$`；`idCard` 必须为 18 位且校验位合法。
- 班次必须未发船；已发船返回 409 `班次已发船，无法下单`。
- 下单即锁座，`expireAt` = 下单时间 + `ferry.order-pay-minutes`（默认 15 分钟）。
- 成功返回 200，`message` 为 `下单成功`，`data` 为订单视图（见 7.3.4，不含 `tickets`）。

#### 7.3.2 支付

```http
POST /api/orders/{id}/pay
```

无请求体。待支付 → 已支付；票转已支付、座位转已售、清空 `expireAt`。非待支付或已超时返回 409。

#### 7.3.3 取消

```http
POST /api/orders/{id}/cancel
```

仅待支付订单可取消；票置已取消、座位释放。

#### 7.3.4 我的订单

```http
GET /api/orders/mine
```

**成功响应** (200)

```json
{
  "success": true,
  "message": "操作成功",
  "data": [
    {
      "id": 501,
      "scheduleId": 10,
      "routeId": 1,
      "passengerId": 4,
      "sellerId": null,
      "totalAmount": 100.00,
      "status": "已支付",
      "payTime": "2026-11-21T15:00:00",
      "expireAt": null,
      "createTime": "2026-11-21T14:58:00",
      "tickets": [
        {
          "id": 901,
          "orderId": 501,
          "scheduleId": 10,
          "seatId": 301,
          "seatNo": "A1",
          "passengerName": "张三",
          "idCard": "440100199001010015",
          "phone": "13800138000",
          "price": 100.00,
          "status": "已支付",
          "refundAmount": null,
          "refundReason": null,
          "refundTime": null,
          "changedFromTicketId": null,
          "changedAt": null
        }
      ]
    }
  ],
  "code": null
}
```

订单视图字段：`id`、`scheduleId`、`routeId`、`passengerId`、`sellerId`、`totalAmount`、`status`、`payTime`、`expireAt`、`createTime`、`tickets[]`。

票视图字段：`id`、`orderId`、`scheduleId`、`seatId`、`seatNo`、`passengerName`、`idCard`、`phone`、`price`、`status`、`refundAmount`、`refundReason`、`refundTime`、`changedFromTicketId`、`changedAt`。

#### 7.3.5 订单详情

```http
GET /api/orders/{id}
```

权限与订单一致：乘车人本人、经手售票员、管理员可看。比列表多返回班次与航线信息，方便页面直接展示。

**成功响应** (200)

```json
{
  "success": true,
  "message": "操作成功",
  "data": {
    "id": 501,
    "scheduleId": 10,
    "routeId": 1,
    "passengerId": 4,
    "sellerId": null,
    "totalAmount": 100.00,
    "status": "已支付",
    "payTime": "2026-11-21T15:00:00",
    "expireAt": null,
    "createTime": "2026-11-21T14:58:00",
    "routeName": "广州-香港",
    "startPort": "广州",
    "endPort": "香港",
    "shipName": "海洋一号",
    "departTime": "2026-11-24T08:00:00",
    "arriveTime": "2026-11-24T10:00:00",
    "tickets": [
      {
        "id": 901,
        "orderId": 501,
        "scheduleId": 10,
        "seatId": 301,
        "seatNo": "A1",
        "passengerName": "张三",
        "idCard": "440100199001010015",
        "phone": "13800138000",
        "price": 100.00,
        "status": "已支付",
        "refundAmount": null,
        "refundReason": null,
        "refundTime": null,
        "changedFromTicketId": null,
        "changedAt": null
      }
    ]
  },
  "code": null
}
```

订单不存在返回 404 `订单不存在`。

### 7.4 退票与改签

#### 7.4.1 退票（按单张票）

```http
POST /api/tickets/{id}/refund
Content-Type: application/json

{ "reason": "行程变更" }
```

请求体可选。`reason` 合法值只有 `行程变更`、`重复购票`、`误购`、`其他`，写入 `ticket.refund_reason`；不传或传空默认 `乘客退票`，非法值返回 400 `退票原因不合法：xxx`。售票员代退接口 `/api/seller/orders/tickets/{id}/refund` 同口径。

按距发船时间分三档：

| 距发船 | 实退金额 `refund_amount` |
|---|---|
| 超过 2 小时 | 票面全额 |
| 不足或等于 2 小时 | 票面 × 80%（扣 20% 手续费） |
| 已发船 | 拒绝，409 `发船后不能退票`，不落流水 |

效果：票 → `已退票`（`refund_amount` = 实退现金，`refund_reason` = 请求里的 `reason`，缺省 `乘客退票`），座位释放，`orders.total_amount` 递减实退额；这张单再没有有效票（`已支付`/`已检票`/`已完成`） 的票时订单 → `已退票`（判据是有效票计数，不是金额归零），否则保持 `已支付`。成功返回 200，`message` 为 `退票成功`，`data` 为票视图。

#### 7.4.2 改签（按单张票，多退少补，限同航线）

```http
POST /api/tickets/{id}/rebook
Content-Type: application/json

{ "targetScheduleId": 13, "targetSeatId": 77 }
```

- 仅已支付票、且所属订单为已支付。
- 改签窗口：**原班次与目标班次**的 `depart_time` 都必须在当前时刻 1 小时之后（两个都满足）；只要满足，改到更早的班次也允许。
- 目标班次必须售票中，且与原班次 `routeId` 相同。
- 目标座位必须属于目标班次，且当前可选。
- 同一乘船人不能在目标班次已有有效票（一人一票，口径见 GLOSSARY「有效票」）。
- 差价 = 新票价 − 原票价。原票 → `已退票`（`refund_reason` = `改签`，`refund_amount` = 退差额时的差额，补差价时为 0），原座位释放；`orders.total_amount` 加上差价（补差价 +，退差额 −）。
- 目标座位置 `已售` 并挂到原订单；新票 `已支付`，`changed_from_ticket_id` 指回原票，`changed_at` 记改签时间。

成功返回 200，`message` 为 `改签成功`，`data` 为新票视图（`changedFromTicketId` 即原票 ID）。

### 7.5 售票员与管理员

代售订单的 `passenger_id` 与 `seller_id` 都挂售票员本人，真实乘船人姓名与身份证写在 `ticket` 表。管理员可直接调用同一组接口。

```http
GET  /api/seller/orders                         订单列表（售票员只看自己，管理员看全部）
POST /api/seller/orders                         代售下单，请求体同 7.3.1
POST /api/seller/orders/{id}/pay                代付
POST /api/seller/orders/{id}/cancel             代取消
POST /api/seller/orders/tickets/{ticketId}/refund   代退，口径同 7.4.1
POST /api/seller/orders/tickets/{ticketId}/rebook   代改签，口径同 7.4.2
```

`GET /api/seller/orders` 支持四个可选 query 参数，同时传时按 AND 组合：

| 参数 | 格式 | 说明 |
|---|---|---|
| `dateFrom` | `YYYY-MM-DD` | 下单日期下界（含） |
| `dateTo` | `YYYY-MM-DD` | 下单日期上界（含） |
| `scheduleId` | number | 只看某个班次（命中订单原始班次或任一张票的班次，改签过的单在目标班次下也查得到） |
| `passengerName` | string | 按乘船人姓名模糊匹配 |

上述四个参数目前只有管理端「全部订单」页接了筛选表单，售票端「代售订单」页暂未提供筛选入口（接口已支持）。

### 7.6 前端页面与路由

hash 路由。同一份「订单 + 票」列表组件按角色挂在不同布局下：

| 角色 | 路径 | 页面文件 |
|---|---|---|
| 乘客 | `/` | `views/passenger/ScheduleSearch.vue` 查询班次 |
| 乘客 | `/schedule/:id` | `views/passenger/SeatSelect.vue` 选座下单 |
| 乘客 | `/my-orders` | `views/passenger/MyOrders.vue` 我的船票（退票 / 改签） |
| 售票员 | `/seller/sell` | `views/seller/SellerSell.vue` 代售开票 |
| 售票员 | `/seller/orders` | `views/seller/SellerOrders.vue` 代售订单（代付 / 代退 / 代改签） |
| 管理员 | `/admin/orders` | `views/admin/AdminOrders.vue` 全部订单 |

复用组件：`components/SeatMap.vue`（座位图）、`components/OrderList.vue`（订单与票表格 + 退改入口）、`components/RebookDialog.vue`（改签选目标班次与座位）。

### 7.7 错误码与典型文案

| 错误码 | HTTP 状态码 | 典型 message |
|---|---|---|
| `CONFLICT` | 409 | 班次不在售票中，无法下单 / 班次已发船，无法下单 / 座位已被占用 / 座位已被他人锁定，请稍后重试 / 乘船人已购买本班次船票（一人一票） / 订单状态不允许支付 / 订单已超时，请重新下单 / 只有待支付订单可以取消 / 只有已支付的船票可以退票 / 只有已支付的船票可以改签 / 发船后不能退票 / 目标班次不在售票中 / 改签仅限同航线 / 距发船不足 1 小时，不能改签 / 目标班次距发船不足 1 小时，不能改签 / 乘船人已购买目标班次船票（一人一票） / 订单金额异常，改签失败 |
| `NOT_FOUND` | 404 | 班次不存在 / 座位不存在：A1 / 船票不存在 / 订单不存在 / 原班次不存在 / 目标座位不存在 |
| `VALIDATION_ERROR` | 400 | 参数校验失败（`@Valid`，如身份证号必须为 18 位、手机号不合法、单订单超过 5 张票）/ 座位号重复：A1 / 同一订单内乘船人身份证号不能重复 / 目标座位与目标班次不匹配 / 退票原因不合法：xxx / 请求体不是合法 JSON / 请求参数格式不正确 |
| `FORBIDDEN` | 403 | 只能操作自己的订单 |

---

## 附录：测试账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | ADMIN |
| seller1 | seller123 | SELLER |
| passenger1 | 123456 | PASSENGER |

密码均为 BCrypt 哈希存储，种子哈希见 `database.sql` 末尾注释。
