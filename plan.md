# 开发路线图

## 已完成

### Phase 0: 基础设施 + 地图服务矩阵 ✅
- [x] Spring Boot 3.2 微服务框架
- [x] Google OR-Tools TSP 求解器（PATH_CHEAPEST_ARC + GUIDED_LOCAL_SEARCH）
- [x] 8 个地图服务提供商（策略模式）
- [x] 国家/地区感知自动路由（17 个区域）
- [x] K-means 聚类降维（Google 费用降低 93%）
- [x] 2-opt + 跨簇 2-opt 局部搜索
- [x] 异步执行 + 状态机 + 轮询
- [x] Redis 距离缓存（可选）
- [x] Swagger API 文档 + Actuator 健康检查

---

## 开发中

### Phase 1: VRP 多车辆路径规划

**目标**：从单车辆 TSP 升级为多车辆 VRP（Vehicle Routing Problem），支持车队调度。

**数据模型已就绪**：
- `RoutePlan.vehicleCount` — 车辆数量
- `RoutePlan.maxCapacityKg` — 单车最大载重

**实现步骤**：

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 1.1 | **VRP 求解器** | 替换 OR-Tools TSP → VRP（PathCheapestArc + GuidedLocalSearch），支持车辆数 + 容量约束 | 3d |
| 1.2 | **多路线结果模型** | `RouteResult` 1:N 扩展，每条路线对应一辆车 | 1d |
| 1.3 | **API 响应适配** | 单路线 → 多路线（`routes[]` 数组），每车独立距离/时长/段 | 1d |
| 1.4 | **测试覆盖** | VRP 求解正确性、车辆数边界、容量约束 | 2d |

---

### Phase 2: 时间窗约束

**目标**：支持每配送点的送达时间窗口（硬约束或软约束）。

**数据模型已就绪**：
- `DeliveryStop.timeWindowStart` / `timeWindowEnd`
- `RoutePlanRequest.StopInfo.timeWindowStart` / `timeWindowEnd`

**实现步骤**：

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 2.1 | **OR-Tools 时间维度** | 在 VRP 模型中加入 `AddDimension` 时间窗，硬约束（必须在窗口内到达） | 2d |
| 2.2 | **等待时间建模** | 早到需要等待，计入累计时间 | 1d |
| 2.3 | **软时间窗** | 允许违反但施加惩罚（`AddSoftSameVehicleConstraint`） | 1d |
| 2.4 | **API 验证** | 时间窗格式校验（ISO-8601）、逻辑校验（start < end） | 0.5d |

---

### Phase 3: 容量 + 其他约束

**目标**：支持载重、体积等多维容量约束。

**数据模型已就绪**：
- `DeliveryStop.weightKg` — 包裹重量
- `RoutePlan.maxCapacityKg` — 车辆最大载重

**实现步骤**：

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 3.1 | **重量容量约束** | OR-Tools `AddDimension` 载重维度 | 1d |
| 3.2 | **体积扩展** | 模型增加 `volumeL` 字段 + 体积容量维度（可选） | 0.5d |
| 3.3 | **多维度容量** | 同时约束重量 + 体积 | 1d |

---

### Phase 4: WebSocket 实时推送

**目标**：替代轮询，通过 WebSocket 实时推送 Phase 进度。

**实现步骤**：

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 4.1 | **WebSocket 配置** | Spring WebSocket STOMP endpoint | 0.5d |
| 4.2 | **进度推送** | 每个 Phase 开始时推送 `{planId, status, progress, message}` | 1d |
| 4.3 | **前端示例** | `map.html` 集成 WebSocket 实时更新 | 1d |
| 4.4 | **优雅降级** | WebSocket 不可用时自动回退 HTTP 轮询 | 0.5d |

---

### Phase 5: 实时路况

**目标**：支持实时/预测路况，而非仅自由流速度。

**实现步骤**：

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 5.1 | **Google 实时路况** | `departure_time=now` + `traffic_model=best_guess` | 1d |
| 5.2 | **Mapbox 实时路况** | Mapbox Directions API `depart_at` 参数 | 0.5d |
| 5.3 | **缓存策略** | 实时路况 TTL 5 分钟（区别于距离缓存的 7 天） | 0.5d |
| 5.4 | **A/B 对比** | 有路况 vs 无路况的时间/距离对比日志 | 0.5d |

---

### Phase 6: 增强与运维

| # | 任务 | 说明 | 预估 |
|---|------|------|------|
| 6.1 | **Prometheus 指标** | 请求量、延迟、API 调用次数、费用追踪 | 1d |
| 6.2 | **限流保护** | 对外部 API 的令牌桶限流（防止超额账单） | 1d |
| 6.3 | **地图可视化增强** | 多路线颜色区分、停靠点时间窗标注、polyline 渲染 | 2d |
| 6.4 | **批量规划** | 一次请求多个仓库/车队批处理 | 2d |
| 6.5 | **多语言 i18n** | 中英文错误消息、API 文档 | 1d |

---

## 技术债务

| # | 项目 | 说明 |
|---|------|------|
| T1 | `DistanceMatrixProvider` 清理 | 移除已废弃的代理类，所有调用方已迁移 |
| T2 | `RouteSegmentInfo` 类型统一 | AMap 内部类 → 共享 `provider.RouteSegmentInfo` |
| T3 | 集成测试 | 添加 ProviderSelector + CountryDetector 集成测试 |
| T4 | AWS 柔性折线解码 | `AwsMapsService` polyline 解码器需要完善 |
