package com.lm.routing.service;

import com.lm.routing.exception.RoutePlanException;
import com.lm.routing.infrastructure.cache.DistanceCacheService;
import com.lm.routing.model.dto.RoutePlanRequest;
import com.lm.routing.model.dto.RoutePlanRequest.StopInfo;
import com.lm.routing.model.dto.RoutePlanResponse;
import com.lm.routing.model.dto.RoutePlanStatusResponse;
import com.lm.routing.model.entity.*;
import com.lm.routing.model.enums.PlanStatus;
import com.lm.routing.service.provider.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Main orchestration service that runs the multi-phase route planning pipeline:
 *
 * Phase 1: Build distance matrix (strategy-dependent: OSRM/Google/Haversine)
 * Phase 2: Solve TSP with OR-Tools
 * Phase 3: Real road enrichment (only for Haversine-based strategies)
 * Phase 4: 2-opt local search refinement
 */
@Slf4j
@Service
public class RoutePlanService {

    private final RoutePlanRepository planRepo;
    private final TspSolverService tspSolver;
    private final AmapRouteService amapService;
    private final RouteRefinementService refinementService;
    private final DistanceCacheService cacheService;
    private final ProviderSelector providerSelector;
    private final GoogleClusterHybridProvider clusterHybridProvider;

    @Value("${routing.amap.max-waypoints-per-call:30}")
    private int maxWaypointsPerCall;

    public RoutePlanService(RoutePlanRepository planRepo,
                            TspSolverService tspSolver,
                            AmapRouteService amapService,
                            RouteRefinementService refinementService,
                            DistanceCacheService cacheService,
                            ProviderSelector providerSelector,
                            GoogleClusterHybridProvider clusterHybridProvider) {
        this.planRepo = planRepo;
        this.tspSolver = tspSolver;
        this.amapService = amapService;
        this.refinementService = refinementService;
        this.cacheService = cacheService;
        this.providerSelector = providerSelector;
        this.clusterHybridProvider = clusterHybridProvider;
    }

    // ===== Public API =====

    @Transactional
    public RoutePlanResponse createPlan(RoutePlanRequest request) {
        // Validate stop count
        if (request.getStops().size() > 500) {
            throw new RoutePlanException.InvalidInputException(
                    "Maximum 500 stops supported, got " + request.getStops().size());
        }

        // Build and persist the route plan
        RoutePlan plan = buildPlanEntity(request);
        plan = planRepo.save(plan);

        log.info("Route plan created: {} with {} stops", plan.getPlanId(), request.getStops().size());

        return toResponse(plan);
    }

    @Async("routePlanningExecutor")
    @Transactional
    public void executePlan(String planId) {
        RoutePlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new RoutePlanException.NotFoundException(planId));

        try {
            // Collect all points: warehouse (index 0) + stops (index 1..N)
            List<GeoPoint> allPoints = collectPoints(plan);
            int n = allPoints.size();
            log.info("Starting route planning for {}: {} total points", planId, n);

            // === Phase 1: Distance Matrix (strategy-dependent) ===
            plan.updateProgress(PlanStatus.BUILDING_MATRIX, "Computing distance matrix", 5);
            planRepo.save(plan);

            GeoPoint warehouse = new GeoPoint(plan.getWarehouseLat(), plan.getWarehouseLng());
            MatrixProvider provider = providerSelector.selectProvider(allPoints, warehouse);
            MatrixResult matrixResult = provider.buildMatrix(allPoints, warehouse);
            double[][] distanceMatrix = matrixResult.matrix();
            log.info("{}: Matrix built via {}: {}×{}, realRoad={}, cost={} elements",
                    planId, matrixResult.strategy(), n, n,
                    matrixResult.isRealRoad(), matrixResult.costElements());

            // === Phase 2: TSP or VRP Solving ===
            int vehicleCount = plan.getVehicleCount() != null ? plan.getVehicleCount() : 1;
            boolean isVrp = vehicleCount > 1;

            if (isVrp) {
                plan.updateProgress(PlanStatus.SOLVING_TSP,
                        "Optimizing multi-vehicle routes with OR-Tools VRP", 15);
                planRepo.save(plan);

                // Collect demands for capacity constraint
                double[] demands = collectDemands(allPoints, plan);
                double maxCapacity = plan.getMaxCapacityKg() != null ? plan.getMaxCapacityKg() : 0;

                TspSolverService.VrpResult vrpResult = tspSolver.solveVrp(
                        distanceMatrix, vehicleCount, demands, maxCapacity);
                List<int[]> vehicleRoutes = vrpResult.routes();

                log.info("{}: VRP solved: {} vehicles, {} nodes, distance={}m, optimal={}",
                        planId, vehicleRoutes.size(), n,
                        String.format("%.0f", vrpResult.totalDistance()), vrpResult.solved());

                // Build multi-vehicle result
                boolean isFallback = !matrixResult.isRealRoad();
                RouteResult result = buildVrpResult(plan, vehicleRoutes, allPoints,
                        distanceMatrix, matrixResult, isFallback);
                plan.setResult(result);
                plan.markCompleted();
                planRepo.save(plan);

                log.info("{}: VRP route planning completed: {}m, {} vehicles",
                        planId, result.getTotalDistanceMeters(),
                        result.getVehicleRoutes().size());
                return; // VRP path ends here (skips refinement for now)
            }

            // === TSP path (single vehicle) ===
            plan.updateProgress(PlanStatus.SOLVING_TSP, "Optimizing route order with OR-Tools", 15);
            planRepo.save(plan);

            TspSolverService.TspResult tspResult = tspSolver.solve(distanceMatrix);
            int[] order = tspResult.route();
            log.info("{}: TSP solved: {} nodes, distance={}m, optimal={}",
                    planId, n, String.format("%.0f", tspResult.totalDistance()), tspResult.solved());

            // If not a round-trip, trim the final return-to-depot (order ends with 0)
            if (!Boolean.TRUE.equals(plan.getReturnToWarehouse()) && order.length > 2) {
                if (order[order.length - 1] == 0) {
                    order = Arrays.copyOf(order, order.length - 1);
                    log.info("{}: Trimmed return-to-depot, {} stops remain", planId, order.length);
                }
            }

            // === Phase 3: Fetch Real Road Distances (only for Haversine-based strategies) ===
            List<AmapRouteService.RouteSegmentInfo> realSegments = List.of();

            if (!matrixResult.isRealRoad()) {
                plan.updateProgress(PlanStatus.REFINING,
                        "Fetching real road distances via " + provider.getName(), 30);
                planRepo.save(plan);

                if (provider instanceof AmapWaypointsProvider amapProvider) {
                    realSegments = fetchRealDistancesFromAmap(allPoints, order, plan);
                } else if (provider instanceof AwsMapsWaypointsProvider awsProvider) {
                    realSegments = fetchRealDistancesFromAws(allPoints, order, plan, awsProvider);
                } else {
                    log.info("{}: No enrichment provider available for strategy {}",
                            planId, matrixResult.strategy());
                }
            } else {
                log.info("{}: Real road matrix from {}, skipping enrichment",
                        planId, matrixResult.strategy());
            }

            // === Phase 3b: Cluster boundary enrichment (CLUSTER_HYBRID only) ===
            // When using K-means clustering, cross-cluster distances are approximated
            // at the centroid level. Query real Google road distances for boundary
            // exit/entry candidates so the 2-opt can discover optimal transitions.
            if (matrixResult.clusterAssignments() != null
                    && matrixResult.clusterList() != null
                    && !matrixResult.clusterList().isEmpty()) {

                plan.updateProgress(PlanStatus.REFINING,
                        "Optimizing cluster boundary transitions with real distances", 70);
                planRepo.save(plan);

                int enriched = clusterHybridProvider.enrichBoundaryEdges(
                        distanceMatrix, order, allPoints,
                        matrixResult.clusterAssignments(), matrixResult.clusterList(), 3);
                log.info("{}: Enriched {} boundary edges with real Google distances",
                        planId, enriched);

                // Run cross-cluster 2-opt with wider window at boundaries
                int[] crossClusterRefined = refinementService.refineCrossCluster(
                        order, distanceMatrix, matrixResult.clusterAssignments(), 3);
                order = crossClusterRefined;
                log.info("{}: Cross-cluster 2-opt applied", planId);
            }

            // === Phase 4: 2-opt Refinement ===
            plan.updateProgress(PlanStatus.REFINING, "Refining route with 2-opt local search", 80);
            planRepo.save(plan);

            int[] refinedOrder = refinementService.refineWithMatrix(order, distanceMatrix);

            // If order changed and we fetched real segments, re-fetch for changed parts
            if (!Arrays.equals(order, refinedOrder) && !matrixResult.isRealRoad()) {
                log.info("{}: Refinement changed route order, re-fetching distances", planId);
                if (provider instanceof AmapWaypointsProvider) {
                    realSegments = fetchRealDistancesFromAmap(allPoints, refinedOrder, plan);
                } else if (provider instanceof AwsMapsWaypointsProvider awsProvider) {
                    realSegments = fetchRealDistancesFromAws(allPoints, refinedOrder, plan, awsProvider);
                }
            }

            // === Build Result ===
            boolean isFallback = !matrixResult.isRealRoad() && realSegments.isEmpty();
            RouteResult result = buildResultV2(plan, realSegments, refinedOrder,
                    allPoints, distanceMatrix, matrixResult, isFallback);
            plan.setResult(result);
            plan.markCompleted();
            planRepo.save(plan);

            log.info("{}: Route planning completed: {}m, {} segments",
                    planId, result.getTotalDistanceMeters(),
                    result.getVehicleRoutes().stream()
                            .mapToInt(vr -> vr.getSegments().size()).sum());

        } catch (Exception e) {
            log.error("{}: Route planning failed: {}", planId, e.getMessage(), e);
            plan.markFailed(e.getMessage());
            planRepo.save(plan);
        }
    }

    @Transactional(readOnly = true)
    public RoutePlanResponse getPlan(String planId) {
        RoutePlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new RoutePlanException.NotFoundException(planId));
        return toResponse(plan);
    }

    @Transactional(readOnly = true)
    public RoutePlanStatusResponse getPlanStatus(String planId) {
        RoutePlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new RoutePlanException.NotFoundException(planId));
        return RoutePlanStatusResponse.builder()
                .planId(plan.getPlanId())
                .status(plan.getStatus().name())
                .phase(plan.getStatusMessage())
                .progress(plan.getProgress())
                .build();
    }

    // ===== Private Helpers =====

    /**
     * Collect per-node demand (weightKg) for capacity-constrained VRP.
     * Index 0 = warehouse (0 demand), indices 1..n-1 = stop weights.
     */
    private double[] collectDemands(List<GeoPoint> allPoints, RoutePlan plan) {
        int n = allPoints.size();
        double[] demands = new double[n];
        demands[0] = 0; // warehouse has no demand
        for (int i = 1; i < n; i++) {
            int stopIdx = i - 1;
            if (stopIdx < plan.getStops().size()) {
                Double w = plan.getStops().get(stopIdx).getWeightKg();
                demands[i] = (w != null) ? w : 0.0;
            }
        }
        return demands;
    }

    /**
     * Build a multi-vehicle RouteResult from VRP solver output.
     */
    private RouteResult buildVrpResult(RoutePlan plan,
                                        List<int[]> vehicleRoutes,
                                        List<GeoPoint> points,
                                        double[][] distanceMatrix,
                                        com.lm.routing.service.provider.MatrixResult matrixResult,
                                        boolean isFallback) {

        long totalDist = 0;
        long totalDur = 0;
        List<VehicleRoute> vRoutes = new ArrayList<>();

        for (int v = 0; v < vehicleRoutes.size(); v++) {
            int[] route = vehicleRoutes.get(v);
            List<RouteSegment> segments = new ArrayList<>();
            long vehicleDist = 0;
            long vehicleDur = 0;

            for (int i = 0; i < route.length - 1; i++) {
                int fromIdx = route[i];
                int toIdx = route[i + 1];
                GeoPoint from = points.get(fromIdx);
                GeoPoint to = points.get(toIdx);

                long dist = Math.round(distanceMatrix[fromIdx][toIdx]);
                Long dur = matrixResult.isRealRoad()
                        ? Math.round(dist / 8.3)
                        : null;

                RouteSegment seg = RouteSegment.builder()
                        .seq(i)
                        .fromStopId(getStopId(plan, fromIdx))
                        .toStopId(getStopId(plan, toIdx))
                        .fromLat(from.getLat()).fromLng(from.getLng())
                        .toLat(to.getLat()).toLng(to.getLng())
                        .distanceMeters(dist)
                        .durationSeconds(dur)
                        .build();
                segments.add(seg);
                vehicleDist += dist;
                if (dur != null) vehicleDur += dur;
            }

            VehicleRoute vRoute = VehicleRoute.builder()
                    .vehicleIndex(v)
                    .totalDistanceMeters(vehicleDist)
                    .totalDurationSeconds(isFallback ? null : vehicleDur)
                    .segments(segments)
                    .build();
            segments.forEach(s -> s.setVehicleRoute(vRoute));
            vRoutes.add(vRoute);
            totalDist += vehicleDist;
            if (!isFallback) totalDur += vehicleDur;
        }

        RouteResult result = RouteResult.builder()
                .resultId(java.util.UUID.randomUUID().toString())
                .totalDistanceMeters(totalDist)
                .totalDurationSeconds(isFallback ? null : totalDur)
                .fallback(isFallback)
                .vehicleRoutes(vRoutes)
                .routePlan(plan)
                .build();

        vRoutes.forEach(vr -> vr.setRouteResult(result));
        return result;
    }

    /**
     * Build the final RouteResult for single-vehicle TSP.
     */
    private RouteResult buildTspResult(RoutePlan plan,
                                        int[] order, List<GeoPoint> points,
                                        double[][] distanceMatrix,
                                        com.lm.routing.service.provider.MatrixResult matrixResult,
                                        boolean isFallback) {

        List<RouteSegment> segments = new ArrayList<>();
        long totalDist = 0;
        long totalDur = 0;

        for (int i = 0; i < order.length - 1; i++) {
            int fromIdx = order[i];
            int toIdx = order[i + 1];
            GeoPoint from = points.get(fromIdx);
            GeoPoint to = points.get(toIdx);

            long dist = Math.round(distanceMatrix[fromIdx][toIdx]);
            Long dur = matrixResult.isRealRoad()
                    ? Math.round(dist / 8.3)
                    : null;

            RouteSegment seg = RouteSegment.builder()
                    .seq(i)
                    .fromStopId(getStopId(plan, fromIdx))
                    .toStopId(getStopId(plan, toIdx))
                    .fromLat(from.getLat()).fromLng(from.getLng())
                    .toLat(to.getLat()).toLng(to.getLng())
                    .distanceMeters(dist)
                    .durationSeconds(dur)
                    .build();
            segments.add(seg);
            totalDist += dist;
            if (dur != null) totalDur += dur;
        }

        VehicleRoute vRoute = VehicleRoute.builder()
                .vehicleIndex(0)
                .totalDistanceMeters(totalDist)
                .totalDurationSeconds(isFallback ? null : totalDur)
                .segments(segments)
                .build();
        segments.forEach(s -> s.setVehicleRoute(vRoute));

        RouteResult result = RouteResult.builder()
                .resultId(java.util.UUID.randomUUID().toString())
                .totalDistanceMeters(totalDist)
                .totalDurationSeconds(isFallback ? null : totalDur)
                .fallback(isFallback)
                .vehicleRoutes(List.of(vRoute))
                .routePlan(plan)
                .build();

        vRoute.setRouteResult(result);
        return result;
    }

    private RoutePlan buildPlanEntity(RoutePlanRequest request) {
        RoutePlanRequest.RouteOptions opts = request.getOptions();
        if (opts == null) opts = new RoutePlanRequest.RouteOptions();

        RoutePlan plan = RoutePlan.builder()
                .planId(UUID.randomUUID().toString())
                .warehouseId(request.getWarehouse().getId())
                .warehouseName(request.getWarehouse().getName())
                .warehouseLat(request.getWarehouse().getLat())
                .warehouseLng(request.getWarehouse().getLng())
                .avoidTolls(opts.getAvoidTolls() != null ? opts.getAvoidTolls() : false)
                .returnToWarehouse(opts.getReturnToWarehouse() != null ? opts.getReturnToWarehouse() : false)
                .vehicleCount(opts.getVehicleCount() != null ? opts.getVehicleCount() : 1)
                .maxCapacityKg(opts.getMaxCapacityKg())
                .status(PlanStatus.PENDING)
                .progress(0)
                .createdAt(Instant.now())
                .build();

        List<DeliveryStop> stops = new ArrayList<>();
        for (StopInfo si : request.getStops()) {
            DeliveryStop stop = DeliveryStop.builder()
                    .stopId(si.getId())
                    .name(si.getName())
                    .address(si.getAddress())
                    .lat(si.getLat())
                    .lng(si.getLng())
                    .weightKg(si.getWeightKg())
                    .routePlan(plan)
                    .build();
            stops.add(stop);
        }
        plan.setStops(stops);
        return plan;
    }

    private List<GeoPoint> collectPoints(RoutePlan plan) {
        List<GeoPoint> points = new ArrayList<>();
        // Index 0: warehouse
        points.add(new GeoPoint(plan.getWarehouseLat(), plan.getWarehouseLng()));
        // Indices 1..N: delivery stops
        for (DeliveryStop stop : plan.getStops()) {
            points.add(new GeoPoint(stop.getLat(), stop.getLng()));
        }
        return points;
    }

    /**
     * Fetch real road distances by calling AMap API in batches of maxWaypointsPerCall.
     * 200 stops → ceil(200/30) ≈ 7 parallel AMap calls.
     */
    private List<AmapRouteService.RouteSegmentInfo> fetchRealDistancesFromAmap(
            List<GeoPoint> points, int[] order, RoutePlan plan) {

        List<CompletableFuture<List<AmapRouteService.RouteSegmentInfo>>> futures = new ArrayList<>();
        int n = order.length;

        // Split the ordered route into batches
        int batchStart = 0; // warehouse is index 0
        while (batchStart < n - 1) {
            int batchEnd = Math.min(batchStart + maxWaypointsPerCall, n - 1);

            final int bs = batchStart;
            final int be = batchEnd;

            CompletableFuture<List<AmapRouteService.RouteSegmentInfo>> future =
                    CompletableFuture.supplyAsync(() -> {
                        GeoPoint origin = points.get(order[bs]);
                        GeoPoint dest = points.get(order[be]);

                        // Collect waypoints between origin and dest (exclusive)
                        List<Double> wpCoords = new ArrayList<>();
                        for (int k = bs + 1; k < be; k++) {
                            GeoPoint wp = points.get(order[k]);
                            wpCoords.add(wp.getLat());
                            wpCoords.add(wp.getLng());
                        }

                        return amapService.fetchRouteSegments(
                                origin.getLat(), origin.getLng(),
                                dest.getLat(), dest.getLng(),
                                wpCoords);
                    });

            futures.add(future);
            batchStart = batchEnd; // next batch starts at previous destination
        }

        // Wait for all parallel calls
        List<AmapRouteService.RouteSegmentInfo> allSegments = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Re-number segments sequentially and fill stop IDs
        Map<Integer, String> indexToStopId = buildIndexToStopIdMap(plan, order);
        for (int i = 0; i < allSegments.size(); i++) {
            AmapRouteService.RouteSegmentInfo seg = allSegments.get(i);
            seg.setSeq(i);
            // Map coordinates back to stop IDs
            seg.setFromStopId(findStopId(points, order, seg.getFromLat(), seg.getFromLng(), plan));
            seg.setToStopId(findStopId(points, order, seg.getToLat(), seg.getToLng(), plan));

            // Cache in Redis
            cacheService.put(seg.getFromLat(), seg.getFromLng(),
                    seg.getToLat(), seg.getToLng(),
                    seg.getDistanceMeters(), seg.getDurationSeconds());
        }

        log.info("Fetched {} real road segments from AMap", allSegments.size());
        return allSegments;
    }

    /**
     * Fetch real road distances via AWS Route Calculator.
     * Converts AWS segments to AMap-compatible format for unified downstream processing.
     */
    private List<AmapRouteService.RouteSegmentInfo> fetchRealDistancesFromAws(
            List<GeoPoint> points, int[] order, RoutePlan plan,
            AwsMapsWaypointsProvider awsProvider) {

        List<CompletableFuture<List<com.lm.routing.service.provider.RouteSegmentInfo>>> futures = new ArrayList<>();
        int n = order.length;

        int batchStart = 0;
        while (batchStart < n - 1) {
            int batchEnd = Math.min(batchStart + maxWaypointsPerCall, n - 1);

            final int bs = batchStart;
            final int be = batchEnd;

            CompletableFuture<List<com.lm.routing.service.provider.RouteSegmentInfo>> future =
                    CompletableFuture.supplyAsync(() -> {
                        GeoPoint origin = points.get(order[bs]);
                        GeoPoint dest = points.get(order[be]);

                        List<Double> wpCoords = new ArrayList<>();
                        for (int k = bs + 1; k < be; k++) {
                            GeoPoint wp = points.get(order[k]);
                            wpCoords.add(wp.getLat());
                            wpCoords.add(wp.getLng());
                        }

                        return awsProvider.getAwsMapsService().fetchRouteSegments(
                                origin.getLat(), origin.getLng(),
                                dest.getLat(), dest.getLng(),
                                wpCoords);
                    });

            futures.add(future);
            batchStart = batchEnd;
        }

        List<com.lm.routing.service.provider.RouteSegmentInfo> allAwsSegments = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Convert to AMap-compatible format
        List<AmapRouteService.RouteSegmentInfo> allSegments = new ArrayList<>();
        Map<Integer, String> indexToStopId = buildIndexToStopIdMap(plan, order);
        for (int i = 0; i < allAwsSegments.size(); i++) {
            com.lm.routing.service.provider.RouteSegmentInfo awsSeg = allAwsSegments.get(i);

            AmapRouteService.RouteSegmentInfo seg = new AmapRouteService.RouteSegmentInfo();
            seg.setSeq(i);
            seg.setDistanceMeters(awsSeg.getDistanceMeters());
            seg.setDurationSeconds(awsSeg.getDurationSeconds());
            seg.setPolyline(awsSeg.getPolyline());
            seg.setFromLat(awsSeg.getFromLat());
            seg.setFromLng(awsSeg.getFromLng());
            seg.setToLat(awsSeg.getToLat());
            seg.setToLng(awsSeg.getToLng());
            seg.setFromStopId(findStopId(points, order, awsSeg.getFromLat(), awsSeg.getFromLng(), plan));
            seg.setToStopId(findStopId(points, order, awsSeg.getToLat(), awsSeg.getToLng(), plan));

            cacheService.put(awsSeg.getFromLat(), awsSeg.getFromLng(),
                    awsSeg.getToLat(), awsSeg.getToLng(),
                    awsSeg.getDistanceMeters(), awsSeg.getDurationSeconds());

            allSegments.add(seg);
        }

        log.info("Fetched {} real road segments from AWS Route Calculator", allSegments.size());
        return allSegments;
    }

    /**
     * Build the final RouteResult from the refined order and distance data.
     */
    private RouteResult buildResultV2(RoutePlan plan,
                                       List<AmapRouteService.RouteSegmentInfo> realSegments,
                                       int[] order, List<GeoPoint> points,
                                       double[][] distanceMatrix,
                                       MatrixResult matrixResult,
                                       boolean isFallback) {

        List<RouteSegment> segments = new ArrayList<>();
        long totalDist = 0;
        long totalDur = 0;

        if (!realSegments.isEmpty()) {
            for (AmapRouteService.RouteSegmentInfo rsi : realSegments) {
                RouteSegment seg = RouteSegment.builder()
                        .seq(rsi.getSeq())
                        .fromStopId(rsi.getFromStopId())
                        .toStopId(rsi.getToStopId())
                        .fromLat(rsi.getFromLat()).fromLng(rsi.getFromLng())
                        .toLat(rsi.getToLat()).toLng(rsi.getToLng())
                        .distanceMeters(rsi.getDistanceMeters())
                        .durationSeconds(rsi.getDurationSeconds())
                        .polyline(rsi.getPolyline())
                        .build();
                segments.add(seg);
                totalDist += rsi.getDistanceMeters();
                totalDur += rsi.getDurationSeconds();
            }
        } else {
            for (int i = 0; i < order.length - 1; i++) {
                int fromIdx = order[i];
                int toIdx = order[i + 1];
                GeoPoint from = points.get(fromIdx);
                GeoPoint to = points.get(toIdx);

                long dist = Math.round(distanceMatrix[fromIdx][toIdx]);
                Long dur = matrixResult.isRealRoad()
                        ? Math.round(dist / 8.3)
                        : null;

                RouteSegment seg = RouteSegment.builder()
                        .seq(i)
                        .fromStopId(getStopId(plan, fromIdx))
                        .toStopId(getStopId(plan, toIdx))
                        .fromLat(from.getLat()).fromLng(from.getLng())
                        .toLat(to.getLat()).toLng(to.getLng())
                        .distanceMeters(dist)
                        .durationSeconds(dur)
                        .build();
                segments.add(seg);
                totalDist += dist;
                if (dur != null) totalDur += dur;
            }
        }

        // Wrap in a single VehicleRoute
        VehicleRoute vRoute = VehicleRoute.builder()
                .vehicleIndex(0)
                .totalDistanceMeters(totalDist)
                .totalDurationSeconds(isFallback ? null : totalDur)
                .segments(segments)
                .build();
        segments.forEach(s -> s.setVehicleRoute(vRoute));

        RouteResult result = RouteResult.builder()
                .resultId(UUID.randomUUID().toString())
                .totalDistanceMeters(totalDist)
                .totalDurationSeconds(isFallback ? null : totalDur)
                .fallback(isFallback)
                .vehicleRoutes(List.of(vRoute))
                .routePlan(plan)
                .build();

        vRoute.setRouteResult(result);
        return result;
    }

    /** @deprecated kept for backward compatibility; prefer buildResultV2 */
    private RouteResult buildResult(RoutePlan plan,
                                     List<AmapRouteService.RouteSegmentInfo> realSegments,
                                     int[] order, List<GeoPoint> points) {

        List<RouteSegment> segments = new ArrayList<>();
        long totalDist = 0;
        long totalDur = 0;
        boolean fallback = realSegments.isEmpty();

        if (fallback) {
            for (int i = 0; i < order.length - 1; i++) {
                GeoPoint from = points.get(order[i]);
                GeoPoint to = points.get(order[i + 1]);
                double dist = HaversineMatrixService.haversineDistance(from, to);

                RouteSegment seg = RouteSegment.builder()
                        .seq(i)
                        .fromStopId(getStopId(plan, order[i]))
                        .toStopId(getStopId(plan, order[i + 1]))
                        .fromLat(from.getLat()).fromLng(from.getLng())
                        .toLat(to.getLat()).toLng(to.getLng())
                        .distanceMeters((long) dist)
                        .durationSeconds((long) (dist / 8.3))
                        .build();
                segments.add(seg);
                totalDist += (long) dist;
            }
        } else {
            for (AmapRouteService.RouteSegmentInfo rsi : realSegments) {
                RouteSegment seg = RouteSegment.builder()
                        .seq(rsi.getSeq())
                        .fromStopId(rsi.getFromStopId())
                        .toStopId(rsi.getToStopId())
                        .fromLat(rsi.getFromLat()).fromLng(rsi.getFromLng())
                        .toLat(rsi.getToLat()).toLng(rsi.getToLng())
                        .distanceMeters(rsi.getDistanceMeters())
                        .durationSeconds(rsi.getDurationSeconds())
                        .polyline(rsi.getPolyline())
                        .build();
                segments.add(seg);
                totalDist += rsi.getDistanceMeters();
                totalDur += rsi.getDurationSeconds();
            }
        }

        // Wrap in VehicleRoute for backward compatibility
        VehicleRoute vRoute = VehicleRoute.builder()
                .vehicleIndex(0)
                .totalDistanceMeters(totalDist)
                .totalDurationSeconds(fallback ? null : totalDur)
                .segments(segments)
                .build();
        segments.forEach(s -> s.setVehicleRoute(vRoute));

        RouteResult result = RouteResult.builder()
                .resultId(UUID.randomUUID().toString())
                .totalDistanceMeters(totalDist)
                .totalDurationSeconds(fallback ? null : totalDur)
                .fallback(fallback)
                .vehicleRoutes(java.util.List.of(vRoute))
                .routePlan(plan)
                .build();

        vRoute.setRouteResult(result);
        return result;
    }

    // ===== Mapping Helpers =====

    private Map<Integer, String> buildIndexToStopIdMap(RoutePlan plan, int[] order) {
        Map<Integer, String> map = new LinkedHashMap<>();
        map.put(0, plan.getWarehouseId());
        // The TSP route may include a return to depot at the end.
        // Only iterate through unique stop indices (skip the final depot return).
        for (int idx : order) {
            if (idx <= 0) {
                map.putIfAbsent(idx, plan.getWarehouseId());
            } else {
                int stopIdx = idx - 1;
                String stopId = stopIdx < plan.getStops().size()
                        ? plan.getStops().get(stopIdx).getStopId()
                        : "UNKNOWN-" + idx;
                map.putIfAbsent(idx, stopId);
            }
        }
        return map;
    }

    private String getStopId(RoutePlan plan, int index) {
        if (index <= 0) return plan.getWarehouseId();
        int stopIdx = index - 1;
        if (stopIdx >= plan.getStops().size()) {
            return plan.getStops().get(plan.getStops().size() - 1).getStopId(); // fallback
        }
        return plan.getStops().get(stopIdx).getStopId();
    }

    private String findStopId(List<GeoPoint> points, int[] order,
                               double lat, double lng, RoutePlan plan) {
        // Find the closest point in our list to the given coordinates
        double bestDist = Double.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i < order.length; i++) {
            GeoPoint p = points.get(order[i]);
            double d = (p.getLat() - lat) * (p.getLat() - lat)
                     + (p.getLng() - lng) * (p.getLng() - lng);
            if (d < bestDist) {
                bestDist = d;
                bestIndex = order[i];
            }
        }
        if (bestIndex <= 0) return plan.getWarehouseId();
        int stopIdx = bestIndex - 1;
        if (stopIdx >= plan.getStops().size()) {
            return plan.getStops().get(plan.getStops().size() - 1).getStopId();
        }
        return plan.getStops().get(stopIdx).getStopId();
    }

    // ===== Response Mapping =====

    private RoutePlanResponse toResponse(RoutePlan plan) {
        // Map warehouse info
        RoutePlanResponse.WarehouseInfo warehouseInfo = RoutePlanResponse.WarehouseInfo.builder()
                .id(plan.getWarehouseId())
                .name(plan.getWarehouseName())
                .lat(plan.getWarehouseLat())
                .lng(plan.getWarehouseLng())
                .build();

        // Map stops with their sequence in the optimized route
        List<RoutePlanResponse.StopInfo> stopInfos = new ArrayList<>();
        if (plan.getStops() != null) {
            for (DeliveryStop stop : plan.getStops()) {
                stopInfos.add(RoutePlanResponse.StopInfo.builder()
                        .id(stop.getStopId())
                        .name(stop.getName())
                        .address(stop.getAddress())
                        .lat(stop.getLat())
                        .lng(stop.getLng())
                        .seq(stop.getSeq())
                        .build());
            }
        }

        RoutePlanResponse.RouteInfo routeInfo = null;
        List<RoutePlanResponse.VehicleRouteInfo> vehicleRouteInfos = null;
        if (plan.getResult() != null) {
            RouteResult result = plan.getResult();

            if (result.getVehicleRoutes() != null && !result.getVehicleRoutes().isEmpty()) {
                vehicleRouteInfos = new ArrayList<>();
                for (VehicleRoute vr : result.getVehicleRoutes()) {
                    List<RoutePlanResponse.SegmentInfo> segInfos = new ArrayList<>();
                    if (vr.getSegments() != null) {
                        for (RouteSegment seg : vr.getSegments()) {
                            segInfos.add(RoutePlanResponse.SegmentInfo.builder()
                                    .seq(seg.getSeq())
                                    .fromStopId(seg.getFromStopId())
                                    .toStopId(seg.getToStopId())
                                    .fromLat(seg.getFromLat())
                                    .fromLng(seg.getFromLng())
                                    .toLat(seg.getToLat())
                                    .toLng(seg.getToLng())
                                    .distanceMeters(seg.getDistanceMeters())
                                    .durationSeconds(seg.getDurationSeconds())
                                    .polyline(seg.getPolyline())
                                    .build());
                        }
                    }
                    vehicleRouteInfos.add(RoutePlanResponse.VehicleRouteInfo.builder()
                            .vehicleIndex(vr.getVehicleIndex())
                            .totalDistanceMeters(vr.getTotalDistanceMeters())
                            .totalDurationSeconds(vr.getTotalDurationSeconds())
                            .segments(segInfos)
                            .build());
                }

                // First vehicle route becomes the backward-compatible single route
                RoutePlanResponse.VehicleRouteInfo first = vehicleRouteInfos.get(0);
                routeInfo = RoutePlanResponse.RouteInfo.builder()
                        .totalDistanceMeters(first.getTotalDistanceMeters())
                        .totalDurationSeconds(first.getTotalDurationSeconds())
                        .fallback(result.getFallback())
                        .segments(first.getSegments())
                        .build();
            }
        }

        return RoutePlanResponse.builder()
                .planId(plan.getPlanId())
                .status(plan.getStatus().name())
                .statusMessage(plan.getStatusMessage())
                .progress(plan.getProgress())
                .warehouse(warehouseInfo)
                .stops(stopInfos)
                .route(routeInfo)
                .routes(vehicleRouteInfos != null && vehicleRouteInfos.size() > 1
                        ? vehicleRouteInfos : null)  // only show routes[] when multi-vehicle
                .createdAt(plan.getCreatedAt())
                .completedAt(plan.getCompletedAt())
                .build();
    }
}
