package com.lm.routing.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TspSolverServiceTest {

    private TspSolverService solver;

    @BeforeEach
    void setUp() {
        solver = new TspSolverService();
        // Inject maxTimeSeconds via reflection or default
        try {
            var field = TspSolverService.class.getDeclaredField("maxTimeSeconds");
            field.setAccessible(true);
            field.set(solver, 10);
        } catch (Exception e) {
            // use default
        }
        solver.init();
    }

    @Test
    void solve_shouldHandleSinglePoint() {
        double[][] matrix = {{0}};
        TspSolverService.TspResult result = solver.solve(matrix);
        assertNotNull(result);
        assertEquals(1, result.route().length);
        assertEquals(0, result.route()[0]);
        assertEquals(0.0, result.totalDistance(), 0.01);
    }

    @Test
    void solve_shouldHandleTwoPoints() {
        double[][] matrix = {
                {0, 1000},
                {1000, 0}
        };
        TspSolverService.TspResult result = solver.solve(matrix);
        assertNotNull(result);
        assertEquals(2, result.route().length);
        assertEquals(0, result.route()[0]);
    }

    @Test
    void solve_shouldReturnReasonableRoute() {
        // 5 points forming a simple scenario
        // Use Haversine matrix for 5 Shanghai points
        List<GeoPoint> points = List.of(
                new GeoPoint(31.2304, 121.4737), // warehouse
                new GeoPoint(31.2450, 121.5050),
                new GeoPoint(31.2200, 121.4900),
                new GeoPoint(31.2500, 121.4800),
                new GeoPoint(31.2350, 121.5100)
        );

        HaversineMatrixService haversineService = new HaversineMatrixService();
        double[][] matrix = haversineService.buildMatrix(points);

        TspSolverService.TspResult result = solver.solve(matrix);

        assertNotNull(result);
        // Route returns to depot: 6 entries for 5 nodes (0→...→0)
        assertTrue(result.route().length >= 5 && result.route().length <= 6,
                "Expected 5-6 nodes, got " + result.route().length);
        // Route should start at 0 (warehouse)
        assertEquals(0, result.route()[0]);
        // Total distance should be positive
        assertTrue(result.totalDistance() > 0);
        // Route should visit all nodes (5 distinct points)
        long distinctCount = java.util.Arrays.stream(result.route()).distinct().count();
        assertEquals(5, distinctCount, "Should visit all 5 distinct nodes");
    }

    @Test
    void solve_shouldHandleTrivialOrder() {
        // If distances are monotonic, the optimal route should be obvious
        double[][] matrix = {
                {0, 1, 100, 100},
                {1, 0, 1, 100},
                {100, 1, 0, 1},
                {100, 100, 1, 0}
        };
        TspSolverService.TspResult result = solver.solve(matrix);

        assertNotNull(result);
        assertEquals(0, result.route()[0]);
        assertTrue(result.totalDistance() <= 103, "Should find near-optimal path: " + result.totalDistance());
    }
}
