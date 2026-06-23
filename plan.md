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

### Phase 1: VRP 多车辆路径规划 ✅ (已完成)

**目标**：从单车辆 TSP 升级为多车辆 VRP（Vehicle Routing Problem），支持车队调度。

**已完成**：
- [x] `TspSolverService.solveVrp()` — OR-Tools 多车辆 + 容量维度
- [x] `VehicleRoute` 实体 + `RouteResult`/`RouteSegment` 关联更新
- [x] API 向后兼容：`route`（单）+ `routes[]`（多）
- [x] 容量约束可选启用
- [x] 6 个 VRP 测试，58/58 通过

---

### Phase 2: 时间窗约束 ✅ (已完成)

**目标**：支持每配送点的送达时间窗口（硬约束）。

**已完成**：
- [x] OR-Tools 时间维度：`addTimeDimension()` — travel_time = dist/speed + serviceTime
- [x] 硬时间窗：`cumulVar.SetRange(start, end)` per-node
- [x] 等待时间：maxSlack = 30 分钟（早到等待）
- [x] ISO-8601 解析：`buildTimeWindows()` → 相对秒数
- [x] TSP + VRP 均支持
- [x] 5 个时间窗测试，63/63 通过

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
| 6.2 | **限流保护** | ✅ 已完成 — 轻量令牌桶，按提供商独立限流 |

---

## 后续增强（按优先级排序）

| # | 优先级 | 项目 | 简易度 | 预估 | 说明 |
|---|--------|------|--------|------|------|
| 1 | 🔴 P0 | README 更新 | ⭐ | 5min | 将"后续扩展"改为已完成 |
| 2 | 🔴 P0 | API Key 认证 | ⭐⭐ | 30min | X-API-Key 请求头校验 |
| 3 | 🟡 P1 | GitHub Actions CI | ⭐⭐ | 30min | 自动 `mvn test` |
| 4 | 🟡 P1 | 端到端 HTTP 测试 | ⭐⭐⭐ | 1h | VRP+时间窗组合场景 |
| 5 | 🟢 P2 | Docker 多阶段构建 | ⭐ | 15min | 减小镜像体积 |
| 6 | 🟢 P2 | 错误消息 i18n | ⭐⭐ | 30min | 中英文错误消息 |
| 7 | 🟢 P2 | 地图可视化增强 | ⭐⭐⭐⭐ | 2h | 多车辆颜色、时间窗标注 |
| 8 | 🟢 P3 | 批量规划 | ⭐⭐⭐⭐⭐ | 1d | 多仓库/车队批处理 |

---

## 技术债务（已解决）

| # | 项目 | 状态 |
|---|------|------|
| T1 | `DistanceMatrixProvider` 清理 | ✅ 已移除 |
| T2 | `RouteSegmentInfo` 类型统一 | ✅ 已统一 |
| T3 | ProviderSelector + CountryDetector 集成测试 | ✅ 72 测试通过 |
| T4 | AWS polyline 解码器 | ✅ 已修复 |
