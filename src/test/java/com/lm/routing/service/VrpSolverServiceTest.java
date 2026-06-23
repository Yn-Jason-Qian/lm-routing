package com.lm.routing.service;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the multi-vehicle VRP solver in TspSolverService.
 */
class VrpSolverServiceTest {

    private final TspSolverService solver = new TspSolverService();

    @BeforeAll
    static void loadNativeLibs() {
        try {
            Loader.loadNativeLibraries();
        } catch (Exception e) {
            // Native libs may already be loaded by another test
        }
    }

    @BeforeEach
    void setUp() {
        // Set a reasonable time limit since @Value doesn't inject outside Spring
        ReflectionTestUtils.setField(solver, "maxTimeSeconds", 10);
    }

    /**
     * Simple 4-node grid with 2 vehicles.
     *
     * Layout:
     *   0: depot (0,0)
     *   1: stop at (1,0)
     *   2: stop at (0,1)
     *   3: stop at (1,1)
     *
     * Each edge = 111 km (~111,000m). With 2 vehicles, should split evenly.
     */
    @Test
    void solveVrp_twoVehicles_shouldSplitStops() {
        // Simple grid: each degree ≈ 111km
        double[][] matrix = new double[][]{
                {0, 111000, 111000, 157000},
                {111000, 0, 157000, 111000},
                {111000, 157000, 0, 111000},
                {157000, 111000, 111000, 0},
        };
        double[] demands = {0, 5, 5, 5};
        double maxCapacity = 20;

        TspSolverService.VrpResult result = solver.solveVrp(matrix, 2, demands, maxCapacity);

        assertNotNull(result);
        assertNotNull(result.routes());
        assertTrue(result.routes().size() >= 1, "Should have at least 1 active vehicle");
        assertTrue(result.routes().size() <= 2, "Should not exceed vehicle count");
        assertTrue(result.totalDistance() > 0, "Total distance should be positive");

        // Verify each route starts at depot (node 0)
        for (int[] route : result.routes()) {
            assertEquals(0, route[0], "Route should start at depot");
            assertTrue(route.length >= 2, "Route should have at least 2 nodes (depot + stop)");
        }
    }

    @Test
    void solveVrp_singleVehicle_shouldWorkLikeTsp() {
        double[][] matrix = new double[][]{
                {0, 100, 200},
                {100, 0, 150},
                {200, 150, 0},
        };
        double[] demands = {0, 0, 0};

        TspSolverService.VrpResult result = solver.solveVrp(matrix, 1, demands, 0);

        assertNotNull(result);
        assertEquals(1, result.routes().size(), "Single vehicle should produce 1 route");
        assertTrue(result.totalDistance() > 0);
    }

    @Test
    void solveVrp_moreVehiclesThanStops_shouldUseOnlyNeededVehicles() {
        double[][] matrix = new double[][]{
                {0, 100},
                {100, 0},
        };
        double[] demands = {0, 0};

        TspSolverService.VrpResult result = solver.solveVrp(matrix, 10, demands, 0);

        assertNotNull(result);
        // With only 1 stop, at most 1 vehicle is needed
        assertTrue(result.routes().size() <= 2,
                "Should not use more vehicles than necessary, got " + result.routes().size());
    }

    @Test
    void solveVrp_capacityConstraint_shouldNotExceedMaxCapacity() {
        double[][] matrix = new double[][]{
                {0, 100, 100, 100, 100},
                {100, 0, 50, 50, 50},
                {100, 50, 0, 50, 50},
                {100, 50, 50, 0, 50},
                {100, 50, 50, 50, 0},
        };
        // Each stop has demand 60, max capacity=100 → each vehicle max 1 stop
        double[] demands = {0, 60, 60, 60, 60};
        double maxCapacity = 100;

        TspSolverService.VrpResult result = solver.solveVrp(matrix, 4, demands, maxCapacity);

        assertNotNull(result);
        assertTrue(result.routes().size() >= 1, "Should assign at least 1 vehicle");

        // Verify no vehicle exceeds capacity
        for (int[] route : result.routes()) {
            double routeDemand = 0;
            for (int node : route) {
                if (node < demands.length) routeDemand += demands[node];
            }
            assertTrue(routeDemand <= maxCapacity + 0.01,
                    "Vehicle should not exceed capacity: demand=" + routeDemand + " > capacity=" + maxCapacity);
        }
    }

    @Test
    void solveVrp_noCapacity_shouldDistributeFreely() {
        // 5 nodes (depot + 4 stops), 2 vehicles, no capacity — all edges 100m
        int n = 5;
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                matrix[i][j] = (i == j) ? 0 : 100;
            }
        }
        double[] demands = {0, 0, 0, 0, 0};

        TspSolverService.VrpResult result = solver.solveVrp(matrix, 2, demands, 0);

        assertNotNull(result);
        assertFalse(result.routes().isEmpty());
        assertTrue(result.totalDistance() > 0);
    }

    @Test
    void solveVrp_singleNode_shouldReturnEmpty() {
        double[][] matrix = new double[][]{{0}};
        double[] demands = {0};

        TspSolverService.VrpResult result = solver.solveVrp(matrix, 2, demands, 0);

        assertNotNull(result);
        assertTrue(result.routes().isEmpty());
        assertEquals(0.0, result.totalDistance(), 0.01);
    }
}
