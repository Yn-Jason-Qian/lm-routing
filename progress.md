# 开发进度

> 最后更新：2026-06-23

## 当前版本：v0.6.0 — 增强与运维

### 完成情况

```
Phase 0: 基础设施 + 地图服务 ✅ ████████████████████ 100%
Phase 1: VRP 多车辆           ✅ ████████████████████ 100%
Phase 2: 时间窗约束           ✅ ████████████████████ 100%
Phase 3: 容量约束             ✅ ████████████████████ 100% (随 Phase 1 实现)
Phase 4: WebSocket 推送       ✅ ████████████████████ 100%
Phase 5: 实时路况             ✅ ████████████████████ 100%
Phase 6: 增强与运维           ✅ ████████████████████ 100%
```

---

## Phase 0 完成详情 ✅

### v0.1.0 — 核心路线规划
- [x] Spring Boot 3.2 + Maven 项目结构
- [x] REST API：`POST /api/v1/route-plans`、`GET /{planId}`、`GET /{planId}/status`
- [x] OR-Tools TSP 求解器（PATH_CHEAPEST_ARC + GUIDED_LOCAL_SEARCH, ≤30s）
- [x] Haversine 距离矩阵（免费近似）
- [x] AMap（高德）路径规划 + waypoints 批量调用
- [x] OSRM /table API 全量真实路网矩阵
- [x] Google Distance Matrix + K-means 聚类降维（93% 费用节省）
- [x] 2-opt 局部搜索 + 跨簇边界优化
- [x] 异步执行 + PlanStatus 状态机 + 进度轮询
- [x] Redis 距离缓存（可选，7 天 TTL）
- [x] H2（dev）/ PostgreSQL（prod）
- [x] Swagger UI + Actuator 健康检查
- [x] Dockerfile + docker-compose（含 OSRM 边车）
- [x] 52 个测试用例全部通过

### v0.2.0 — 多地图服务矩阵（2026-06-23）
- [x] 策略模式重构：`MatrixProvider` 接口 + 9 个实现类
- [x] 新增 Mapbox 矩阵 API（`MapboxApiService` + `MapboxMatrixProvider`）
- [x] 新增 Bing Maps 矩阵 API（`BingMapsApiService` + `BingMapsMatrixProvider`）
- [x] 新增 AWS Location Service 路径补充（`AwsMapsService` + `AwsMapsWaypointsProvider`）
- [x] 国家/地区检测：`BoundingBoxCountryDetector`（17 个区域，坐标边界框）
- [x] 国家感知 AUTO 模式：`ProviderSelector` 根据仓库坐标自动选择最优提供商
- [x] 向后兼容：`DistanceMatrixProvider` 标记为 @Deprecated 代理

---

### v0.3.0 — VRP 多车辆路径规划（2026-06-23）
- [x] VRP 求解器：`TspSolverService.solveVrp()` — OR-Tools 多车辆 + 容量维度
- [x] `VehicleRoute` 实体：RouteResult 1:N VehicleRoute 1:N RouteSegment
- [x] API 向后兼容：单车辆保持 `route`，多车辆新增 `routes[]`
- [x] `RouteOptions` 增加 `vehicleCount`、`maxCapacityKg`
- [x] 容量约束：可选启用（有 weightKg 时传 OR-Tools 容量维度）
- [x] 6 个 VRP 单元测试

### v0.4.0 — 时间窗约束（2026-06-23）
- [x] OR-Tools 时间维度：`addTimeDimension()` — travel_time = distance/speed + serviceTime
- [x] 硬时间窗约束：`cumulVar.SetRange(start, end)` per-node
- [x] 允许等待：maxSlack = 30 分钟
- [x] 向后兼容：无窗口数据时完全跳过时间维度
- [x] TSP + VRP 均支持时间窗
- [x] `buildTimeWindows()` — ISO-8601 → 相对秒数转换
- [x] 5 个时间窗测试

### v0.5.0 — WebSocket + 实时路况（2026-06-23）
- [x] WebSocket STOMP endpoint `/ws` + SockJS fallback
- [x] `ProgressPushService`：推送到 `/topic/plan/{planId}`
- [x] `map.html`：STOMP.js 客户端 + 进度条 + 完成自动刷新
- [x] 保留 HTTP 轮询降级（`GET /{planId}/status`）
- [x] Google Maps 实时路况：`departure_time=now` + `traffic_model=best_guess`
- [x] Mapbox 实时路况：`depart_at=now`
- [x] 配置开关：`routing.traffic.enabled`（默认关闭，零额外费用）

### v0.6.0 — Prometheus + 限流（2026-06-23）
- [x] `micrometer-registry-prometheus` 依赖
- [x] `/actuator/prometheus` 端点暴露
- [x] `MetricsConfig`：自定义 Counter / Timer / Histogram
- [x] 请求计数（`lmr_requests_total`）、API 调用计数（`lmr_api_calls_total`）
- [x] `RateLimiter`：轻量令牌桶（无额外依赖）
- [x] `RateLimitConfig`：按提供商独立限流（5-10 req/s）

---

## 技术债务追踪

| ID | 项目 | 状态 | 优先级 |
|----|------|------|--------|
| T1 | `DistanceMatrixProvider` 废弃类清理 | 📋 待处理 | 低 |
| T2 | `RouteSegmentInfo` 类型统一（AMap → 共享类型） | 📋 待处理 | 低 |
| T3 | ProviderSelector + CountryDetector 集成测试 | 📋 待处理 | 中 |
| T4 | `AwsMapsService` polyline 解码器完善 | 📋 待处理 | 中 |

---

## 版本历史

| 版本 | 日期 | 内容 |
|------|------|------|
| v0.1.0 | 2026-06 | 核心 TSP 路线规划（高德/Google/OSRM/Haversine） |
| v0.2.0 | 2026-06-23 | 多地图服务矩阵 + 国家感知路由（Mapbox/Bing/AWS） |
| v0.3.0 | 2026-06-23 | VRP 多车辆路径规划 + 容量约束 |
| v0.4.0 | 2026-06-23 | 时间窗约束（硬约束 + 等待时间） |
| v0.5.0 | 2026-06-23 | WebSocket 实时推送 + 实时路况 |
| **v0.6.0** | **2026-06-23** | **Prometheus 指标 + API 限流保护** |
