# lm-routing

末端派送路线规划微服务 —— 为物流末端配送系统提供单车辆 TSP 路线优化能力，支持全球 7 大地图服务 + 国家/地区智能路由。

## 核心设计

### 策略引擎（AUTO 模式 — 国家感知）

系统根据仓库坐标自动检测国家/地区，选择最优的地图服务提供商：

```
AUTO 模式决策树:
  ├─ 仓库坐标 → CountryDetector 检测国家/地区
  │     ├─ 🇨🇳 中国 → AMap（高德）> OSRM > Google > Mapbox
  │     ├─ 🇺🇸 北美 → Mapbox > Google > Bing > OSRM
  │     ├─ 🇪🇺 欧洲 → OSRM > Mapbox > Google > Bing
  │     ├─ 🇯🇵🇰🇷 日韩 → Google > Mapbox > OSRM
  │     ├─ 🇮🇳 印度 → Mapbox > Google > OSRM
  │     └─ 🌏 全球 → Google > OSRM > Mapbox > Bing
  │
  └─ 地区首选不可用 → 全局可用性链回退
        ├─ OSRM 可用 → 全量真实路网矩阵（$0）
        ├─ 高德 Key → Haversine 免费矩阵 + waypoints 批量精修
        ├─ Google Key + ≤50 点 → Google 全量矩阵
        ├─ Google Key + >50 点 → K-means 聚类分层（降费 93%）
        ├─ Mapbox Token → Mapbox 全量矩阵
        ├─ Bing Key → Bing 全量矩阵
        ├─ AWS Key → Haversine + AWS Route Calculator
        └─ 全无 → Haversine 纯免费（自动降级）
```

### 处理流程

```
POST /api/v1/route-plans → 202 (异步)
  │
  ├─ Phase 1: ProviderSelector → MatrixProvider.buildMatrix()
  │     ├─ 国家检测（BoundingBoxCountryDetector）
  │     ├─ OSRM /table API           → 全量真实路网
  │     ├─ Mapbox /directions-matrix  → 全量真实路网
  │     ├─ Bing /DistanceMatrix       → 全量真实路网
  │     ├─ K-means 聚类 + Google API  → 分层真实路网（降费 93%）
  │     └─ Haversine 瞬时计算          → 免费近似
  │
  ├─ Phase 2: OR-Tools TSP 求解（GUIDED_LOCAL_SEARCH, ≤30s）
  │     └─ 全局最优 / 近优访问顺序
  │
  ├─ Phase 3: 路网精修（仅 Haversine 策略需要）
  │     ├─ AMap waypoints → 高德路径规划 + waypoints 批量调用（~7 次）
  │     └─ AWS Route Calculator → AWS 路径计算 + waypoints 批量调用
  │
  ├─ Phase 3b: 簇边界优化（仅 CLUSTER_HYBRID 策略）
  │     ├─ 边界充实：识别簇间衔接点，用 Google API 真实距离替换质心近似
  │     │     └─ 每边界查询 3×3 候选进出口对（约 90 元素 / 200 点）
  │     └─ 跨簇 2-opt：在簇边界区域用 3× 宽窗口局部搜索消除绕路
  │
  ├─ Phase 4: 2-opt 局部搜索微调
  │     └─ 矩阵快速模式（<1ms）
  │
  └─ COMPLETED → 完整路线（含距离、时长、polyline）
```

### 地图服务提供商对比

| 提供商 | 类型 | 覆盖优势 | 200 点费用 | 矩阵精度 | 部署难度 |
|--------|------|----------|-----------|----------|----------|
| **OSRM** | 全量矩阵 | 🌏 全球（OSM 数据） | **$0** | 全量真实路网 | 需 Docker |
| **AMap（高德）** | Haversine + 补充 | 🇨🇳 中国最佳 | ¥0.07 | 直线 + 路径精修 | 仅需 API Key |
| **Mapbox** | 全量矩阵 | 🇺🇸🇪🇺🇮🇳🇦🇺 欧美印澳 | ~$2 | 全量真实路网 | 仅需 Token |
| **Google** | 聚类分层 | 🌏 全球最全 | ~$14 | 簇内真实 + 边界充实 | 仅需 API Key |
| **Bing Maps** | 全量矩阵 | 🇺🇸🇪🇺 欧美 | ~$4 | 全量真实路网 | 仅需 API Key |
| **AWS Maps** | Haversine + 补充 | 🌏 AWS 区域 | ~$0.10 | 直线 + 路径精修 | 仅需 API Key |
| **Haversine** | 近似 | — | $0 | 65-80% 精度 | 零依赖 |

## 技术栈

| 组件 | 选型 |
|------|------|
| 框架 | Spring Boot 3.2 |
| JDK | 17+ |
| 构建 | Maven |
| 优化引擎 | Google OR-Tools 9.9（GUIDED_LOCAL_SEARCH） |
| 矩阵策略 | OSRM / Google Matrix / AMap / Mapbox / Bing / AWS / Haversine |
| 国家检测 | 坐标边界框（BoundingBoxCountryDetector） |
| 聚类降维 | K-means++ |
| 缓存 | Redis（可选） |
| 数据库 | H2（开发）/ PostgreSQL（生产） |
| API 文档 | SpringDoc OpenAPI 3 |
| 健康检查 | Spring Boot Actuator |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+

### 零依赖启动（Haversine 纯免费模式）

```bash
cd lm-routing
mvn spring-boot:run
```

无需任何外部服务即可运行。所有距离用 Haversine 球面公式计算，精度约为真实路网的 65%-80%。

### 国内部署（高德地图）

```bash
export AMAP_API_KEY=your_amap_key
mvn spring-boot:run
```

### 北美/欧洲部署（Mapbox，推荐）

```bash
export MAPBOX_ACCESS_TOKEN=your_mapbox_token
mvn spring-boot:run
```

### 国外部署（OSRM 自建，推荐）

```bash
# 1. 下载 OSM 路网数据
mkdir osm-data
curl -o osm-data/shanghai.osm.pbf https://download.geofabrik.de/asia/china/shanghai-latest.osm.pbf
# 或全球任意区域：https://download.geofabrik.de/

# 2. 启动 OSRM + 应用
export OSM_FILE=shanghai.osm.pbf
docker-compose up osrm app
```

首次启动 OSRM 需要 5-30 分钟处理路网数据（取决于区域大小），之后每次启动秒级就绪。

### 国外部署（Google Maps API）

```bash
export GOOGLE_MAPS_API_KEY=your_google_api_key
mvn spring-boot:run
```

> ≤50 点直接全量矩阵，>50 点自动启用 K-means 聚类降维。

### 国外部署（Bing Maps）

```bash
export BING_MAPS_API_KEY=your_bing_api_key
mvn spring-boot:run
```

### AWS 环境部署（AWS Location Service）

```bash
export AWS_MAPS_API_KEY=your_aws_api_key
export AWS_REGION=us-east-1
mvn spring-boot:run
```

### Docker 部署

```bash
# 仅应用 + Redis
docker-compose up app redis

# 含 OSRM（零费用全量真实路网）
OSM_FILE=china-latest.osm.pbf docker-compose up osrm app redis
```

应用启动后访问：
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/api-docs
- **Health**: http://localhost:8080/actuator/health

## API 文档

### POST /api/v1/route-plans

创建路线规划任务。立即返回 `202 Accepted`，后台异步求解。

**Request:**

```json
{
  "warehouse": {
    "id": "WH-001",
    "name": "上海浦东仓库",
    "lat": 31.2304,
    "lng": 121.4737
  },
  "stops": [
    {
      "id": "PKG-001",
      "name": "张三",
      "address": "浦东新区XX路XX号",
      "lat": 31.2450,
      "lng": 121.5050
    }
  ],
  "options": {
    "avoidTolls": true,
    "returnToWarehouse": false,
    "maxSolveTimeSeconds": 30
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `warehouse` | object | ✅ | 仓库信息 |
| `warehouse.id` | string | ✅ | 仓库唯一标识 |
| `warehouse.lat` | number | ✅ | 纬度 (-90 ~ 90) |
| `warehouse.lng` | number | ✅ | 经度 (-180 ~ 180) |
| `stops` | array | ✅ | 配送点列表，最多 500 个 |
| `stops[].id` | string | ✅ | 停靠点唯一标识 |
| `stops[].lat` | number | ✅ | 纬度 |
| `stops[].lng` | number | ✅ | 经度 |
| `stops[].weightKg` | number | ❌ | 包裹重量（VRP 预留） |
| `stops[].timeWindowStart` | string | ❌ | 时间窗开始（预留） |
| `stops[].timeWindowEnd` | string | ❌ | 时间窗结束（预留） |
| `options.avoidTolls` | boolean | ❌ | 避开收费路段，默认 false |
| `options.returnToWarehouse` | boolean | ❌ | 是否返回仓库，默认 false |
| `options.maxSolveTimeSeconds` | integer | ❌ | TSP 求解超时，默认 30 |

**Response** `202 Accepted`:

```json
{
  "planId": "e44dbd54-b796-456d-ab71-8de8c7abbf24",
  "status": "PENDING",
  "progress": 0,
  "createdAt": "2026-06-02T10:30:00Z"
}
```

### GET /api/v1/route-plans/{planId}

获取路线规划结果。

**Response** `200 OK`:

```json
{
  "planId": "e44dbd54-b796-456d-ab71-8de8c7abbf24",
  "status": "COMPLETED",
  "progress": 100,
  "route": {
    "totalDistanceMeters": 156000,
    "totalDurationSeconds": 14400,
    "fallback": false,
    "segments": [
      {
        "seq": 0,
        "fromStopId": "WH-001",
        "toStopId": "PKG-042",
        "fromLat": 31.2304,
        "fromLng": 121.4737,
        "toLat": 31.2450,
        "toLng": 121.5050,
        "distanceMeters": 3200,
        "durationSeconds": 600,
        "polyline": "encoded_polyline_string"
      }
    ]
  },
  "createdAt": "2026-06-02T10:30:00Z",
  "completedAt": "2026-06-02T10:30:45Z"
}
```

| 字段 | 说明 |
|------|------|
| `route.totalDistanceMeters` | 总距离（米） |
| `route.totalDurationSeconds` | 预计驾驶时长（秒），fallback 模式为空 |
| `route.fallback` | 是否降级为 Haversine 直线距离 |
| `route.segments[].polyline` | 地图 polyline 编码，可直接渲染 |

### GET /api/v1/route-plans/{planId}/status

轻量级进度查询，用于前端轮询。

```json
{
  "planId": "uuid",
  "status": "REFINING",
  "phase": "正在获取真实路网距离...",
  "progress": 75
}
```

### 状态机

```
PENDING → BUILDING_MATRIX → SOLVING_TSP → REFINING → COMPLETED
                                                  ↘ FAILED
```

| 状态 | 进度 | 说明 |
|------|------|------|
| `PENDING` | 0% | 已接受，排队中 |
| `BUILDING_MATRIX` | 5% | 正在构建距离矩阵（策略因配置而异） |
| `SOLVING_TSP` | 15% | OR-Tools 正在求解最优访问顺序 |
| `REFINING` | 30-90% | 路网精修 + 簇边界优化 + 2-opt 局部搜索 |
| `COMPLETED` | 100% | 路线规划完成 |
| `FAILED` | — | 出错，查看 `statusMessage` |

## 配置

```yaml
routing:
  # 策略: AUTO | OSRM | MAPBOX | BING | GOOGLE_FULL | CLUSTER_HYBRID | AMAP_WAYPOINTS | AWS_ROUTES | HAVERSINE_FULL
  # AUTO = 国家感知 + 可用性自动选择
  strategy: AUTO

  # 高德地图（中国最佳）
  amap:
    key: ${AMAP_API_KEY:}
    base-url: https://restapi.amap.com
    max-waypoints-per-call: 30

  # Google Maps（全球最全）
  google:
    api-key: ${GOOGLE_MAPS_API_KEY:}
    base-url: https://maps.googleapis.com

  # OSRM 自建路由引擎（免费，推荐）
  osrm:
    base-url: ${OSRM_BASE_URL:http://localhost:5000}
    timeout-seconds: 60

  # Mapbox（北美/欧洲/印度最佳）
  mapbox:
    access-token: ${MAPBOX_ACCESS_TOKEN:}
    base-url: https://api.mapbox.com

  # Bing Maps（全球覆盖，支持卡车路线）
  bing:
    api-key: ${BING_MAPS_API_KEY:}
    base-url: https://dev.virtualearth.net

  # AWS Location Service（AWS 环境最优）
  aws:
    api-key: ${AWS_MAPS_API_KEY:}
    region: ${AWS_REGION:us-east-1}
    route-calculator: Default

  # K-means 聚类参数
  clustering:
    max-points-per-cluster: 20
    cluster-tsp-algorithm: OR_TOOLS    # OR_TOOLS | NEAREST_NEIGHBOR

  # OR-Tools 求解器
  solver:
    max-time-seconds: 30

  # 2-opt 局部搜索
  refinement:
    2opt-window-size: 10

  # Redis 距离缓存
  cache:
    redis-ttl-days: 7
```

### 策略选择指南

| 场景 | 策略 | 环境变量 | AUTO 行为 |
|------|------|---------|----------|
| 国内配送 | `AUTO` | `AMAP_API_KEY=xxx` | 检测到中国 → 自动选高德 |
| 北美配送 | `AUTO` | `MAPBOX_ACCESS_TOKEN=xxx` | 检测到美国 → 自动选 Mapbox |
| 欧洲（有 Docker） | `AUTO` | `OSRM_BASE_URL=http://osrm:5000` | 检测到欧洲 → 自动选 OSRM |
| 日本/韩国 | `AUTO` | `GOOGLE_MAPS_API_KEY=xxx` | 检测到日韩 → 自动选 Google |
| 印度配送 | `AUTO` | `MAPBOX_ACCESS_TOKEN=xxx` | 检测到印度 → 自动选 Mapbox |
| AWS 托管环境 | `AUTO` | `AWS_MAPS_API_KEY=xxx` | 最便宜的付费方案 |
| 调试/离线 | `HAVERSINE_FULL` | 无需任何 Key | — |
| 强制 OSRM | `OSRM` | `OSRM_BASE_URL=http://osrm:5000` | — |
| 强制 Mapbox | `MAPBOX` | `MAPBOX_ACCESS_TOKEN=xxx` | — |
| 强制 Google 聚类 | `CLUSTER_HYBRID` | `GOOGLE_MAPS_API_KEY=xxx` | — |

## 性能基准

测试环境：JDK 21, Windows 10, H2 内存数据库, 无 Redis, 无外部 API

| 停靠点 | 矩阵构建 | TSP 求解 | 2-opt 微调 | 总耗时 | 内存 |
|--------|---------|---------|-----------|--------|------|
| 5 | <1ms | ~30s | <1ms | ~30s | ~80MB |
| 50 | <1ms | ~30s | <1ms | ~30s | ~80MB |
| 200 | <1ms | ~30s | <1ms | **~31s** | ~120MB |
| 500 | <1ms | ~30s | <5ms | ~30s | ~250MB |

> TSP 设置了 30 秒超时，200+ 点返回近优解（通常与全局最优差距 < 5%）。
> OSRM 模式下矩阵构建约需 30-60 秒（取决于区域大小），但可获得全量真实路网距离。
> CLUSTER_HYBRID 模式额外增加边界充实（每边界 ~200ms Google API 调用）和跨簇 2-opt（<5ms），
> 200 点约增加 2-3 秒，但簇间衔接精度显著提升。

## 费用估算

### 全局费用对比（200 个配送点）

| 提供商 | API 调用模式 | 费用 | 说明 |
|--------|-------------|------|------|
| **OSRM** | 无 | **$0** | 自托管，无限免费 |
| **Haversine** | 无 | **$0** | CPU 计算，无需外部服务 |
| **AWS Maps** | ~7 次路由计算 | **~$0.0035** | 最便宜的付费方案 |
| **AMap（高德）** | ~7 次路径规划 | **¥0.07** | 每日免费 5000 次 |
| **Mapbox** | 40,000 矩阵元素 | **~$2** | $0.001/element |
| **Bing Maps** | 40,000 矩阵元素 | **~$4** | $0.002/element |
| **Google（聚类）** | ~3,090 元素 | **~$14** | 节省 93% vs 全量 |

### 国内（高德地图）

| 停靠点 | API 调用次数 | 预估费用 |
|--------|-------------|---------|
| 5-30 | 1 次 | ¥0.01 |
| 30-200 | 7 次 | ¥0.07 |
| 200-500 | 17 次 | ¥0.17 |

> 高德每日免费额度 5000 次，中小规模业务基本够用。

### 国外（Google Maps）

| 停靠点 | 策略 | 矩阵元素数 | 边界优化 | 预估费用 |
|--------|------|-----------|---------|---------|
| ≤50 | 全量 | 2,500 | — | ~$12 |
| 200 | K-means 聚类 | ~3,000 | +~90 | **~$15** |
| 500 | K-means 聚类 | ~6,000 | +~150 | ~$31 |
| 200（暴力全矩阵）| — | 40,000 | — | ~$200 |

> 边界优化：簇间衔接点用 Google API 真实距离替换质心近似，额外消耗约 K×9 个元素（K 为簇数）。

### 国外（OSRM 自建）

| 停靠点 | 费用 |
|--------|------|
| 任意规模 | **$0** |

### Mapbox / Bing Maps

| 停靠点 | Mapbox 费用 | Bing 费用 |
|--------|------------|----------|
| ≤25 | ~$0.63 | ~$1.25 |
| 50 | ~$2.50 | ~$5 |
| 200 | ~$2 | ~$4 |
| 500 | ~$12 | ~$25 |

## 项目结构

```
lm-routing/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── README.md
└── src/
    ├── main/java/com/lm/routing/
    │   ├── LmRoutingApplication.java
    │   ├── config/
    │   │   ├── AsyncConfig.java              # 异步线程池
    │   │   ├── RedisConfig.java              # Redis 序列化
    │   │   ├── RestTemplateConfig.java       # HTTP 客户端
    │   │   └── WebConfig.java                # CORS 配置
    │   ├── controller/
    │   │   └── RoutePlanController.java      # REST API
    │   ├── model/
    │   │   ├── dto/                          # 请求/响应 DTO
    │   │   ├── entity/                       # JPA Entity + Repository
    │   │   └── enums/PlanStatus.java         # 状态机枚举
    │   ├── service/
    │   │   ├── RoutePlanService.java         # 主编排器（四阶段）
    │   │   ├── DistanceMatrixProvider.java   # [已废弃] 向后兼容代理
    │   │   ├── CountryDetector.java          # 国家检测接口
    │   │   ├── BoundingBoxCountryDetector.java # 坐标边界框检测
    │   │   ├── CountryRegion.java            # 国家/地区枚举 + 优先级
    │   │   ├── HaversineMatrixService.java   # Haversine 距离矩阵
    │   │   ├── TspSolverService.java         # OR-Tools 求解器
    │   │   ├── KMeansClusteringService.java  # K-means++ 聚类
    │   │   ├── OsrmService.java              # OSRM /table API
    │   │   ├── AmapRouteService.java         # 高德路径规划 API
    │   │   ├── GoogleMapsApiService.java     # Google Distance Matrix API
    │   │   ├── RouteRefinementService.java   # 2-opt + 跨簇 2-opt
    │   │   └── provider/
    │   │       ├── MatrixProvider.java       # 核心策略接口
    │   │       ├── MatrixResult.java         # 矩阵结果 record
    │   │       ├── RouteSegmentInfo.java     # 路线段通用模型
    │   │       ├── ProviderCapability.java   # 提供商能力分类
    │   │       ├── ProviderSelector.java     # 策略路由器（国家感知）
    │   │       ├── OsrmMatrixProvider.java   # OSRM 策略
    │   │       ├── GoogleFullMatrixProvider.java  # Google 全量策略
    │   │       ├── GoogleClusterHybridProvider.java # Google 聚类策略
    │   │       ├── AmapWaypointsProvider.java  # 高德路径补充策略
    │   │       ├── HaversineOnlyProvider.java   # Haversine 纯近似策略
    │   │       ├── MapboxMatrixProvider.java    # Mapbox 策略
    │   │       ├── BingMapsMatrixProvider.java  # Bing Maps 策略
    │   │       ├── AwsMapsWaypointsProvider.java # AWS 路径补充策略
    │   │       ├── mapbox/
    │   │       │   └── MapboxApiService.java   # Mapbox API 封装
    │   │       ├── bing/
    │   │       │   └── BingMapsApiService.java # Bing Maps API 封装
    │   │       └── aws/
    │   │           └── AwsMapsService.java     # AWS Location Service 封装
    │   ├── infrastructure/cache/
    │   │   └── DistanceCacheService.java     # Redis 距离缓存
    │   └── exception/                        # 全局异常处理
    └── test/java/com/lm/routing/
        ├── controller/
        │   └── RoutePlanControllerTest.java
        ├── service/
        │   ├── HaversineMatrixServiceTest.java
        │   ├── TspSolverServiceTest.java
        │   ├── KMeansClusteringServiceTest.java
        │   └── RouteRefinementServiceTest.java
        ├── exception/
        │   └── GlobalExceptionHandlerTest.java
        └── infrastructure/cache/
            └── DistanceCacheServiceTest.java
```

## 后续扩展

详见 [plan.md](./plan.md) 完整开发路线图和 [progress.md](./progress.md) 进度追踪。

| Phase | 功能 | 状态 | 数据模型 |
|-------|------|------|----------|
| 1 | **多车辆 VRP** | 📋 规划中 | `vehicleCount` 已预留 |
| 2 | **时间窗约束** | 📋 规划中 | `timeWindowStart/End` 已预留 |
| 3 | **容量约束** | 📋 规划中 | `weightKg` 已预留 |
| 4 | **WebSocket 推送** | 📋 规划中 | Phase 进度实时推送 |
| 5 | **实时路况** | 📋 规划中 | departure_time 参数 |
| 6 | **增强与运维** | 📋 规划中 | Prometheus / 限流 / 批量 |

## License

MIT
