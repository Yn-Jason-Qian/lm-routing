package com.lm.routing.service;

import com.google.ortools.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for time-window-constrained TSP and VRP solving.
 */
class TimeWindowSolverTest {

    private final TspSolverService solver = new TspSolverService();

    @BeforeAll
    static void loadNativeLibs() {
        try {
            Loader.loadNativeLibraries();
        } catch (Exception e) {
            // Already loaded
        }
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(solver, "maxTimeSeconds", 10);
        ReflectionTestUtils.setField(solver, "maxWaitingSec", 1800L);
        ReflectionTestUtils.setField(solver, "avgSpeedKmh", 30.0);
        ReflectionTestUtils.setField(solver, "horizonHours", 12);
    }

    /**
     * TSP with 3 stops, each 100m apart (~12s at 30 km/h).
     * Time windows are wide open — should produce a valid route.
     */
    @Test
    void solveTsp_withTimeWindows_shouldProduceValidRoute() {
        double[][] matrix = {
                {0, 100, 100, 100},
                {100, 0, 100, 100},
                {100, 100, 0, 100},
                {100, 100, 100, 0},
        };
        // Wide open windows: all stops need to be visited within [0, 3600] seconds
        long[][] timeWindows = {
                {0, 3600},     // depot: 1h window
                {0, 3600},     // stop 1
                {0, 3600},     // stop 2
                {0, 3600},     // stop 3
        };

        TspSolverService.TspResult result = solver.solve(matrix, timeWindows, 60, 8.33);

        assertNotNull(result);
        assertTrue(result.route().length >= 2, "Route should have at least 2 nodes");
        assertEquals(0, result.route()[0], "Route should start at depot");
        assertTrue(result.totalDistance() > 0, "Should have positive distance");
    }

    /**
     * Two stops with non-overlapping time windows. With 1 vehicle,
     * the solver must order them to match the windows.
     *
     * Stop 1: [0, 600] — must visit first
     * Stop 2: [3600, 7200] — must visit second
     */
    @Test
    void solveTsp_sequentialTimeWindows_shouldOrderCorrectly() {
        double[][] matrix = {
                {0, 1000, 1000},
                {1000, 0, 2000},
                {1000, 2000, 0},
        };
        // Stop 1 early morning, Stop 2 late morning
        long[][] timeWindows = {
                {0, 7200},        // depot: all day
                {0, 600},         // stop 1: first hour (must visit early)
                {3600, 7200},     // stop 2: later (can't visit early)
        };

        TspSolverService.TspResult result = solver.solve(matrix, timeWindows, 0, 8.33);

        assertNotNull(result);
        // Stop 1 (index 1) should come before Stop 2 (index 2) due to time windows
        int[] route = result.route();
        int idx1 = -1, idx2 = -1;
        for (int i = 0; i < route.length; i++) {
            if (route[i] == 1) idx1 = i;
            if (route[i] == 2) idx2 = i;
        }
        assertTrue(idx1 >= 0 && idx2 >= 0, "Both stops should be in route");
        assertTrue(idx1 < idx2,
                "Stop 1 (early window) should come before Stop 2 (late window)");
    }

    /**
     * VRP with time windows — 2 vehicles, each stop has a distinct time slot.
     */
    @Test
    void solveVrp_withTimeWindows_shouldAssignCorrectly() {
        // 4 nodes, all edges 100m
        int n = 4;
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                matrix[i][j] = (i == j) ? 0 : 100;

        double[] demands = {0, 5, 5, 5};
        // Stops 1 and 2: early morning, Stop 3: late afternoon
        long[][] timeWindows = {
                {0, 43200},       // depot: 12h
                {0, 7200},        // stop 1: morning
                {0, 7200},        // stop 2: morning
                {14400, 43200},   // stop 3: afternoon
        };

        TspSolverService.VrpResult result = solver.solveVrp(
                matrix, 2, demands, 20, timeWindows, 60, 8.33);

        assertNotNull(result);
        assertFalse(result.routes().isEmpty(), "Should have at least 1 active vehicle");
        assertTrue(result.totalDistance() > 0);
    }

    /**
     * Backward compatible: no time windows should work same as before.
     */
    @Test
    void solveTsp_noTimeWindows_shouldWorkSameAsBefore() {
        double[][] matrix = {
                {0, 100, 200},
                {100, 0, 150},
                {200, 150, 0},
        };

        TspSolverService.TspResult result = solver.solve(matrix, null, 60, 8.33);

        assertNotNull(result);
        assertTrue(result.route().length >= 2);
        assertEquals(0, result.route()[0]);
    }

    /**
     * Impossibly tight time windows — solver should still return something.
     */
    @Test
    void solveTsp_tightWindow_shouldStillReturnRoute() {
        double[][] matrix = {
                {0, 100000, 100000},
                {100000, 0, 50000},
                {100000, 50000, 0},
        };
        // Extremely tight window (only 1 second!)
        long[][] timeWindows = {
                {0, 43200},       // depot
                {0, 1},           // stop 1: impossibly tight
                {0, 43200},       // stop 2: wide open
        };

        // May return null solution (window too tight), should not crash
        TspSolverService.TspResult result = solver.solve(matrix, timeWindows, 0, 8.33);

        // Even with tight windows, the solver should produce something
        // (initial PATH_CHEAPEST_ARC still builds a solution)
        assertNotNull(result, "Should return a result (even if suboptimal)");
    }
}
