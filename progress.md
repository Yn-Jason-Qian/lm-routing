# 开发进度

> 最后更新：2026-06-23

## 当前版本：v0.2.0 — 多地图服务矩阵

### 完成情况

```
Phase 0: 基础设施 + 地图服务 ✅ ████████████████████ 100%
Phase 1: VRP 多车辆            ⬜ ░░░░░░░░░░░░░░░░░░░░   0%
Phase 2: 时间窗约束            ⬜ ░░░░░░░░░░░░░░░░░░░░   0%
Phase 3: 容量约束              ⬜ ░░░░░░░░░░░░░░░░░░░░   0%
Phase 4: WebSocket 推送        ⬜ ░░░░░░░░░░░░░░░░░░░░   0%
Phase 5: 实时路况              ⬜ ░░░░░░░░░░░░░░░░░░░░   0%
Phase 6: 增强与运维            ⬜ ░░░░░░░░░░░░░░░░░░░░   0%
```

---

## Phase 0 完成详情 ✅

### v0.1.0 — 核心路线规划（已完成）
- [x] Spring Boot 3.2 + Maven 项目结构
- [x] REST API：`POST /api/v1/route-plans`（创建）、`GET /{planId}`（查询）、`GET /{planId}/status`（轮询）
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

### v0.2.0 — 多地图服务矩阵（2026-06-23 完成）
- [x] 策略模式重构：`MatrixProvider` 接口 + 9 个实现类
- [x] 新增 Mapbox 矩阵 API（`MapboxApiService` + `MapboxMatrixProvider`）
- [x] 新增 Bing Maps 矩阵 API（`BingMapsApiService` + `BingMapsMatrixProvider`）
- [x] 新增 AWS Location Service 路径补充（`AwsMapsService` + `AwsMapsWaypointsProvider`）
- [x] 国家/地区检测：`BoundingBoxCountryDetector`（17 个区域，坐标边界框）
- [x] 国家感知 AUTO 模式：`ProviderSelector` 根据仓库坐标自动选择最优提供商
- [x] 向后兼容：`DistanceMatrixProvider` 标记为 @Deprecated 代理
- [x] AWS 路由补充：Haversine 矩阵 → TSP → AWS Route Calculator 路径精修
- [x] `application.yml` 增加 mapbox/bing/aws 配置块
- [x] CLAUDE.md / README.md 更新
- [x] 52/52 测试通过，无回归

---

## Phase 1: VRP 多车辆 — 规划中

**依赖**：Phase 0 数据模型已就绪（`vehicleCount`, `maxCapacityKg`）

| 任务 | 状态 | 负责人 | 备注 |
|------|------|--------|------|
| 1.1 VRP 求解器 | ⬜ 未开始 | — | OR-Tools RoutingModel + AddDimension |
| 1.2 多路线结果 | ⬜ 未开始 | — | `RouteResult` 1:N `Route` |
| 1.3 API 适配 | ⬜ 未开始 | — | 单路线 → `routes[]` 数组 |
| 1.4 测试 | ⬜ 未开始 | — | VRP 求解场景覆盖 |

---

## Phase 2-6: 待排期

详见 [plan.md](./plan.md) 完整路线图。

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
| **v0.2.0** | **2026-06-23** | **多地图服务矩阵 + 国家感知路由（Mapbox/Bing/AWS）** |
