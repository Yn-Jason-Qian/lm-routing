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
     * Solve TSP for the given distance matrix.
     * Node 0 is the depot (warehouse). The route always starts from node 0.
     *
     * @param distanceMatrix n×n matrix of distances between all nodes (in meters)
     * @return the optimized route order and total distance
     */
    public TspResult solve(double[][] distanceMatrix) {
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

        // Step 3: Configure search parameters
        RoutingSearchParameters searchParams = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(
                        LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(com.google.protobuf.Duration.newBuilder()
                        .setSeconds(maxTimeSeconds).build())
                .setLogSearch(false)
                .build();

        // Step 4: Solve
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
}
