# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 构建与测试

```bash
# 构建（跳过测试）
mvn package -DskipTests

# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=HaversineMatrixServiceTest
mvn test -Dtest=TspSolverServiceTest

# 启动应用（dev 环境，H2 内存数据库，无外部依赖）
mvn spring-boot:run

# 使用指定环境启动
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

应用启动在 8080 端口。Swagger UI 在 `/swagger-ui.html`，健康检查在 `/actuator/health`。

## 架构

这是一个**末端配送路线优化微服务**——给定一个仓库和多达 500 个配送点，使用 Google OR-Tools 求解最优 TSP（旅行商问题）访问顺序，然后通过多地图服务（高德/Google/OSRM/Mapbox/Bing/AWS）获取真实道路距离来丰富路线数据。

### 处理流程（核心）

`RoutePlanService.executePlan()` 异步执行，分为 4 个阶段：

1. **BUILDING_MATRIX（构建矩阵）** — `ProviderSelector.selectProvider()` 根据国家/地区和可用性选择最优 `MatrixProvider`，然后 `MatrixProvider.buildMatrix()` 构建 N×N 距离矩阵
2. **SOLVING_TSP（求解 TSP）** — `TspSolverService.solve()` 使用 PATH_CHEAPEST_ARC + GUIDED_LOCAL_SEARCH 运行 OR-Tools，上限 30 秒
3. **REFINING（路网补充）** — 仅当矩阵为 Haversine 估算（非真实道路）时执行。通过 AMap 或 AWS Route Calculator 并行获取真实道路距离和折线
4. **REFINING（2-opt 优化）** — `RouteRefinementService.refineWithMatrix()` 在距离矩阵上应用滑动窗口 2-opt 局部搜索

状态机：`PENDING → BUILDING_MATRIX → SOLVING_TSP → REFINING → COMPLETED`（任何状态都可以转为 `FAILED`）。

### 策略引擎（ProviderSelector + MatrixProvider 接口）

策略模式通过 `MatrixProvider` 接口实现，所有实现类由 Spring 自动发现。`ProviderSelector` 负责路由选择：

**AUTO 模式（国家/地区感知）：**
1. 通过 `BoundingBoxCountryDetector` 从仓库坐标检测国家/地区
2. 根据 `CountryRegion` 枚举中的优先级列表依次尝试
3. 若地区首选不可用，回退到全局可用性链

**可用策略（MatrixProvider 实现）：**

| 优先级 | 策略 | 依赖条件 | 费用 | 类型 |
|--------|------|----------|------|------|
| 1 | `OSRM` | 自托管 OSRM Docker 边车 | 免费 | 全量真实道路矩阵 |
| 2 | `AMAP_WAYPOINTS` | `AMAP_API_KEY` 环境变量 | ~¥0.01/次 | Haversine + 高德路径补充 |
| 3 | `MAPBOX` | `MAPBOX_ACCESS_TOKEN` 环境变量 | ~$2/200点 | 全量真实道路矩阵 |
| 4 | `GOOGLE_FULL` | `GOOGLE_MAPS_API_KEY`，≤50 点 | ~$12 | 完整 Google 距离矩阵 |
| 5 | `CLUSTER_HYBRID` | `GOOGLE_MAPS_API_KEY`，>50 点 | ~$14/200点 | K-means 聚类（节省 93%） |
| 6 | `BING` | `BING_MAPS_API_KEY` 环境变量 | ~$4/200点 | 全量真实道路矩阵 |
| 7 | `AWS_ROUTES` | `AWS_MAPS_API_KEY` 环境变量 | ~$0.10/200点 | Haversine + AWS 路径补充 |
| 99 | `HAVERSINE_ONLY` | 无 | 免费 | 球面近似（65-80% 精度） |

通过 `routing.strategy` 配置属性强制指定策略。

**国家/地区偏好：**
- 🇨🇳 中国 → AMAP → OSRM → Google → Mapbox
- 🇺🇸 美国 → Mapbox → Google → Bing → OSRM
- 🇪🇺 欧洲 → OSRM → Mapbox → Google → Bing
- 🇯🇵 日本 → Google → Mapbox → OSRM
- 🇮🇳 印度 → Mapbox → Google → OSRM
- 🌏 全局 → Google → OSRM → Mapbox → Bing

### 包结构

新增 provider 包结构：
- `service/provider/` — `MatrixProvider` 接口、`ProviderSelector`、`MatrixResult`
- `service/provider/mapbox/` — Mapbox API 封装
- `service/provider/bing/` — Bing Maps API 封装
- `service/provider/aws/` — AWS Location Service 封装
- `service/CountryDetector.java` — 国家检测接口
- `service/BoundingBoxCountryDetector.java` — 基于坐标边界框的实现
- `service/CountryRegion.java` — 国家/地区枚举 + 提供商优先级

### 异步执行

控制器立即返回 `202 Accepted`。`RoutePlanService.executePlan()` 在 `routePlanningExecutor`（4-8 线程，队列容量 100，拒绝策略 `CallerRunsPolicy`）上运行。通过 JPA 实体状态更新跟踪进度——客户端轮询 `GET /{planId}/status`。

### 高德途经点获取失败时

在第 3 阶段中，`fetchRealDistances()` 并行调用高德 API。如果高德不可用（未配置 API Key），`realSegments` 将为空，`buildResultV2()` 会回退到基于矩阵的路线段构建，标记 `fallback=true`。

### OR-Tools 原生库加载

`TspSolverService.init()` 在启动时通过 `@PostConstruct` 调用 `Loader.loadNativeLibraries()`。如果加载失败，日志会警告但应用仍会启动——后续 TSP 求解尝试将在运行时抛出异常。在 Windows 上，OR-Tools DLL 必须位于 `java.library.path` 上。

### 实体关系

- `RoutePlan` — 聚合根，包含计划元数据、仓库坐标和选项
- `DeliveryStop` — 子实体（OneToMany 关联自 RoutePlan），每个配送点的经纬度
- `RouteResult` — 与 RoutePlan 一对一，保存最终路线，包含总距离/总时间和有序路线段
- `RouteSegment` — RouteResult 的子实体，最终路线中的每条边

所有实体使用 `planId` 作为 UUID 字符串主键（非自动生成）。

### 数据库

- **dev**：H2 内存数据库（`jdbc:h2:mem:lmrouting`），H2 控制台在 `/h2-console`，DDL 自动更新
- **prod**：PostgreSQL，仅校验 DDL（`ddl-auto: validate`），通过 HikariCP 连接池

Redis 是可选的——仅用于 `DistanceCacheService`（缓存点对点真实道路距离）。健康检查会标记 Redis 为禁用状态，确保应用在没有 Redis 时仍能保持健康。
