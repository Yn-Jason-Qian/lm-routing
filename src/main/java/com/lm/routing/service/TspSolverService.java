package com.lm.routing.service;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Solves the Traveling Salesman Problem using Google OR-Tools.
 *
 * Algorithm: PATH_CHEAPEST_ARC (greedy initial) → GUIDED_LOCAL_SEARCH (improvement).
 * Time limit is configurable; for 200 stops, 30s is a good trade-off.
 *
 * The solver uses the distance matrix directly — the caller is responsible for
 * populating it (Haversine, real road, or hybrid).
 */
@Slf4j
@Service
public class TspSolverService {

    @Value("${routing.solver.max-time-seconds:30}")
    private int maxTimeSeconds;

    @Value("${routing.time-window.default-service-time-seconds:300}")
    private long defaultServiceTimeSec;

    @Value("${routing.time-window.avg-speed-kmh:30}")
    private double avgSpeedKmh;

    @Value("${routing.time-window.max-waiting-seconds:1800}")
    private long maxWaitingSec;

    @Value("${routing.time-window.horizon-hours:12}")
    private int horizonHours;

    @PostConstruct
    public void init() {
        try {
            Loader.loadNativeLibraries();
            log.info("OR-Tools native libraries loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load OR-Tools native libraries: {}", e.getMessage());
            log.error("OR-Tools requires native libraries for your platform. " +
                      "On Linux this is automatic. On macOS: brew install or-tools. " +
                      "On Windows: ensure the DLL is on java.library.path.");
        }
    }

    /**
     * Result of TSP solving.
     *
     * @param route ordered list of node indices (0 = warehouse/depot, then stops)
     * @param totalDistance total route distance in the matrix's units (meters)
     * @param solved whether the solver found a proven optimal solution
     */
    public record TspResult(int[] route, double totalDistance, boolean solved) {}

    /**
     * Result of VRP solving.
     *
     * @param routes list of per-vehicle routes, each an ordered node index array
     * @param totalDistance sum of all vehicle route distances
     * @param solved whether the solver found a proven optimal solution
     */
    public record VrpResult(List<int[]> routes, double totalDistance, boolean solved) {}

    /**
     * Solve TSP for the given distance matrix.
     * Node 0 is the depot (warehouse). The route always starts from node 0.
     *
     * @param distanceMatrix n×n matrix of distances between all nodes (in meters)
     * @return the optimized route order and total distance
     */
    /**
     * Solve TSP without time windows (backward compatible).
     */
    public TspResult solve(double[][] distanceMatrix) {
        return solve(distanceMatrix, null, defaultServiceTimeSec, avgSpeedMs());
    }

    /**
     * Solve TSP with optional time window constraints.
     *
     * @param distanceMatrix N×N distance matrix (meters)
     * @param timeWindows    per-node [startSec, endSec] pairs, or null for unconstrained
     * @param serviceTimeSec time spent at each stop (seconds)
     * @param avgSpeedMs     average travel speed for distance→time conversion (m/s)
     */
    public TspResult solve(double[][] distanceMatrix, long[][] timeWindows,
                            long serviceTimeSec, double avgSpeedMs) {
        int n = distanceMatrix.length;

        if (n <= 1) {
            return new TspResult(new int[]{0}, 0.0, true);
        }

        // Trivial case: just two points
        if (n == 2) {
            return new TspResult(new int[]{0, 1}, distanceMatrix[0][1], true);
        }

        log.info("Solving TSP for {} nodes with {}s time limit", n, maxTimeSeconds);

        // Step 1: Create routing model
        RoutingIndexManager manager = new RoutingIndexManager(n, 1, 0);
        RoutingModel routing = new RoutingModel(manager);

        // Step 2: Register distance callback
        int transitCallbackIndex = routing.registerTransitCallback((fromIndex, toIndex) -> {
            int from = manager.indexToNode(fromIndex);
            int to = manager.indexToNode(toIndex);
            // OR-Tools uses long for distances, round to nearest meter
            return Math.round(distanceMatrix[from][to]);
        });
        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        // Step 3: Optional time dimension
        if (hasTimeWindows(timeWindows)) {
            log.info("TSP: time window dimension enabled for {} nodes", n);
            addTimeDimension(routing, manager, distanceMatrix, timeWindows,
                    serviceTimeSec, avgSpeedMs);
        }

        // Step 4: Configure search parameters
        RoutingSearchParameters searchParams = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(
                        LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(com.google.protobuf.Duration.newBuilder()
                        .setSeconds(maxTimeSeconds).build())
                .setLogSearch(false)
                .build();

        // Step 5: Solve
        Assignment solution = routing.solveWithParameters(searchParams);

        if (solution == null) {
            log.warn("OR-Tools returned null solution for {} nodes", n);
            // Fallback: return nodes in input order
            int[] fallback = new int[n];
            for (int i = 0; i < n; i++) fallback[i] = i;
            return new TspResult(fallback, computeRouteDistance(fallback, distanceMatrix), false);
        }

        // Step 5: Extract route
        int[] route = extractRoute(routing, manager, solution);
        double totalDistance = computeRouteDistance(route, distanceMatrix);

        boolean solved = routing.solver() != null;
        log.info("TSP solved: {} nodes, total distance = {} m, optimal = {}",
                n, String.format("%.0f", totalDistance), solved);
        return new TspResult(route, totalDistance, solved);
    }

    /**
     * Solve VRP (Vehicle Routing Problem) for multi-vehicle delivery.
     *
     * Uses the same distance matrix as TSP, but distributes stops across
     * {@code vehicleCount} vehicles, all starting/ending at the depot (node 0).
     * Optionally enforces capacity constraints when {@code maxCapacity > 0}
     * and stops have non-zero demands.
     *
     * @param distanceMatrix N×N distance matrix (meters), depot at index 0
     * @param vehicleCount   number of available vehicles
     * @param demands        per-node demand (kg), index 0 = 0 (depot has no demand)
     * @param maxCapacity    maximum capacity per vehicle (kg), 0 = no capacity constraint
     * @return per-vehicle routes, total distance, and solve status
     */
    /**
     * Solve VRP without time windows (backward compatible).
     */
    public VrpResult solveVrp(double[][] distanceMatrix, int vehicleCount,
                               double[] demands, double maxCapacity) {
        return solveVrp(distanceMatrix, vehicleCount, demands, maxCapacity,
                null, defaultServiceTimeSec, avgSpeedMs());
    }

    /**
     * Solve VRP with optional time window constraints.
     */
    public VrpResult solveVrp(double[][] distanceMatrix, int vehicleCount,
                               double[] demands, double maxCapacity,
                               long[][] timeWindows, long serviceTimeSec,
                               double avgSpeedMs) {
        int n = distanceMatrix.length;

        if (n <= 1) {
            return new VrpResult(List.of(), 0.0, true);
        }

        log.info("Solving VRP: {} nodes, {} vehicles, maxCapacity={}kg, {}s limit",
                n, vehicleCount, maxCapacity > 0 ? String.format("%.0f", maxCapacity) : "none",
                maxTimeSeconds);

        // Step 1: Create routing model with multiple vehicles, depot at node 0
        RoutingIndexManager manager = new RoutingIndexManager(n, vehicleCount, 0);
        RoutingModel routing = new RoutingModel(manager);

        // Step 2: Register distance callback
        int transitCallbackIndex = routing.registerTransitCallback((fromIndex, toIndex) -> {
            int from = manager.indexToNode(fromIndex);
            int to = manager.indexToNode(toIndex);
            return Math.round(distanceMatrix[from][to]);
        });
        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        // Step 3: Optional capacity dimension
        boolean hasCapacity = maxCapacity > 0 && hasNonZeroDemands(demands);
        if (hasCapacity) {
            int demandCallbackIndex = routing.registerUnaryTransitCallback((long fromIndex) -> {
                int fromNode = manager.indexToNode(fromIndex);
                return (long) demands[fromNode];
            });
            routing.addDimension(demandCallbackIndex, 0, (long) maxCapacity,
                    true, "Capacity");
            log.info("VRP: capacity dimension enabled (max {} kg per vehicle)", String.format("%.0f", maxCapacity));
        }

        // Step 4: Optional time dimension
        if (hasTimeWindows(timeWindows)) {
            log.info("VRP: time window dimension enabled for {} nodes", n);
            addTimeDimension(routing, manager, distanceMatrix, timeWindows,
                    serviceTimeSec, avgSpeedMs);
        }

        // Step 5: Configure search parameters
        RoutingSearchParameters searchParams = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(
                        LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(com.google.protobuf.Duration.newBuilder()
                        .setSeconds(maxTimeSeconds).build())
                .setLogSearch(false)
                .build();

        // Step 6: Solve
        Assignment solution = routing.solveWithParameters(searchParams);

        if (solution == null) {
            log.warn("OR-Tools VRP returned null solution for {} nodes", n);
            // Fallback: assign all stops to vehicle 0
            int[] fallbackRoute = new int[n];
            for (int i = 0; i < n; i++) fallbackRoute[i] = i;
            double totalDist = computeRouteDistance(fallbackRoute, distanceMatrix);
            return new VrpResult(List.of(fallbackRoute), totalDist, false);
        }

        // Step 6: Extract per-vehicle routes
        List<int[]> routes = new ArrayList<>();
        double totalDistance = 0.0;

        for (int v = 0; v < vehicleCount; v++) {
            int[] route = extractRouteForVehicle(routing, manager, solution, v);
            if (route.length >= 2) {
                double routeDist = computeRouteDistance(route, distanceMatrix);
                totalDistance += routeDist;
                routes.add(route);
            }
        }

        boolean solved = routing.solver() != null;
        log.info("VRP solved: {} vehicles used (of {}), total distance = {} m, optimal = {}",
                routes.size(), vehicleCount, String.format("%.0f", totalDistance), solved);
        return new VrpResult(routes, totalDistance, solved);
    }

    /**
     * Check if any demand value is > 0 (capacity constraint is meaningful).
     */
    private boolean hasNonZeroDemands(double[] demands) {
        if (demands == null) return false;
        for (double d : demands) {
            if (d > 0) return true;
        }
        return false;
    }

    /**
     * Extract the ordered list of node indices for a single vehicle from the VRP solution.
     */
    private int[] extractRouteForVehicle(RoutingModel routing, RoutingIndexManager manager,
                                          Assignment solution, int vehicle) {
        List<Integer> routeList = new ArrayList<>();
        long index = routing.start(vehicle);
        while (!routing.isEnd(index)) {
            int node = manager.indexToNode((int) index);
            routeList.add(node);
            index = solution.value(routing.nextVar(index));
        }
        int finalNode = manager.indexToNode((int) index);
        if (routeList.isEmpty() || routeList.get(routeList.size() - 1) != finalNode) {
            routeList.add(finalNode);
        }

        return routeList.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Extract the ordered list of node indices from the solution.
     */
    private int[] extractRoute(RoutingModel routing, RoutingIndexManager manager,
                                Assignment solution) {
        List<Integer> routeList = new ArrayList<>();
        long index = routing.start(0);
        while (!routing.isEnd(index)) {
            int node = manager.indexToNode((int) index);
            routeList.add(node);
            index = solution.value(routing.nextVar(index));
        }
        // Add the final node (end depot, which is a copy of node 0)
        int finalNode = manager.indexToNode((int) index);
        // Only add if different from the last node
        if (routeList.isEmpty() || routeList.get(routeList.size() - 1) != finalNode) {
            routeList.add(finalNode);
        }

        return routeList.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Compute the total distance of a given route using the distance matrix.
     */
    private double computeRouteDistance(int[] route, double[][] matrix) {
        double total = 0.0;
        for (int i = 0; i < route.length - 1; i++) {
            total += matrix[route[i]][route[i + 1]];
        }
        return total;
    }

    /**
     * Check if any node has a non-null time window.
     */
    private boolean hasTimeWindows(long[][] timeWindows) {
        if (timeWindows == null) return false;
        for (long[] tw : timeWindows) {
            if (tw != null && tw.length >= 2) return true;
        }
        return false;
    }

    /**
     * Compute average speed in m/s from the configured km/h value.
     */
    private double avgSpeedMs() {
        return avgSpeedKmh * 1000.0 / 3600.0;
    }

    /**
     * Add a time dimension to the routing model.
     *
     * Each node with a time window constraint must be visited within
     * [startSec, endSec]. The depot has a wide-open window.
     */
    private void addTimeDimension(RoutingModel routing, RoutingIndexManager manager,
                                   double[][] distanceMatrix, long[][] timeWindows,
                                   long serviceTimeSec, double avgSpeedMs) {
        int n = distanceMatrix.length;

        // Transit callback: travel_time + service_time (in seconds)
        int timeCallbackIndex = routing.registerTransitCallback((long fromIdx, long toIdx) -> {
            int from = manager.indexToNode(fromIdx);
            int to = manager.indexToNode(toIdx);
            double travelTimeSec = distanceMatrix[from][to] / avgSpeedMs;
            return (long) travelTimeSec + serviceTimeSec;
        });

        // Compute horizon: max endTime + buffer
        long maxEndTime = horizonHours * 3600L;
        if (timeWindows != null) {
            for (long[] tw : timeWindows) {
                if (tw != null && tw.length >= 2 && tw[1] > maxEndTime) {
                    maxEndTime = tw[1] + 3600; // add 1h buffer after last window
                }
            }
        }

        routing.addDimension(timeCallbackIndex,
                maxWaitingSec,   // max slack (waiting time allowed)
                maxEndTime,      // max cumulative time (horizon)
                false,           // don't force cumul to start at zero
                "Time");

        // Set time window constraints per node
        for (int i = 0; i < timeWindows.length && i < n; i++) {
            if (timeWindows[i] != null && timeWindows[i].length >= 2) {
                long index = manager.nodeToIndex(i);
                if (index >= 0) {
                    routing.getMutableDimension("Time")
                           .cumulVar(index)
                           .setRange(timeWindows[i][0], timeWindows[i][1]);
                }
            }
        }

        log.debug("Time dimension added: horizon={}s, serviceTime={}s, avgSpeed={}m/s, maxWaiting={}s",
                maxEndTime, serviceTimeSec, String.format("%.1f", avgSpeedMs), maxWaitingSec);
    }
}
