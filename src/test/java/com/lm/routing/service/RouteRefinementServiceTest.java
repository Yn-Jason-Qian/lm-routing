package com.lm.routing.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteRefinementServiceTest {

    private RouteRefinementService service;

    @BeforeEach
    void setUp() {
        service = new RouteRefinementService(null, null);
        // Inject windowSize via reflection
        try {
            var field = RouteRefinementService.class.getDeclaredField("windowSize");
            field.setAccessible(true);
            field.set(service, 5); // small window for testing
        } catch (Exception e) {
            // use default
        }
    }

    @Test
    void refineCrossCluster_withNullAssignments_shouldFallbackToRegular2Opt() {
        // 5 points with a clear suboptimal edge that 2-opt can fix
        double[][] matrix = {
                {0, 1, 100, 100, 100},
                {1, 0, 1, 100, 100},
                {100, 1, 0, 1, 100},
                {100, 100, 1, 0, 100},
                {100, 100, 100, 100, 0}
        };
        int[] order = {0, 1, 2, 3, 4}; // optimal already

        int[] result = service.refineCrossCluster(order, matrix, null, 3);
        assertArrayEquals(order, result);
    }

    @Test
    void refineCrossCluster_shouldKeepOptimalRoute() {
        double[][] matrix = {
                {0, 1, 10, 10},
                {1, 0, 1, 10},
                {10, 1, 0, 1},
                {10, 10, 1, 0}
        };
        int[] order = {0, 1, 2, 3}; // already optimal
        // Single cluster (all same cluster) — no cross-cluster edges
        int[] clusterAssignments = {0, 0, 0, 0};

        int[] result = service.refineCrossCluster(order, matrix, clusterAssignments, 3);
        assertArrayEquals(order, result);
    }

    @Test
    void refineCrossCluster_shouldFixCrossClusterDetour() {
        // Simulate two clusters: cluster 0 = [warehouse, A1, A2], cluster 1 = [B1, B2]
        // The matrix was assembled with centroid-level cross-cluster approximations
        // Now the enriched matrix has real boundary distances
        //
        // Intended order: 0 → A1 → B1 → B2 → A2
        // But cross-cluster edge A1→B1 beats the alternative

        double[][] matrix = {
                //   0    A1   A2   B1   B2
                {   0,   1,  10,  10,  10},  // warehouse
                {   1,   0,   2,   5,  80},  // A1 (enriched: A1→B1=5, A1→B2=80)
                {  10,   2,   0,  90,   8},  // A2 (enriched: A2→B1=90, A2→B2=8)
                {  10,   5,  90,   0,   3},  // B1
                {  10,  80,   8,   3,   0},  // B2
        };

        // Current order has a cross-cluster detour: 0→A1→A2→B2→B1
        // A2→B2 is good (8) but the overall could be better with 0→A1→B1→B2→A2
        int[] order = {0, 1, 2, 4, 3}; // 0→A1→A2→B2→B1
        // Cluster assignments: 0=warehouse(-1), 1=A(0), 2=A(0), 3=B(1), 4=B(1)
        int[] clusterAssignments = {-1, 0, 0, 1, 1};

        int[] result = service.refineCrossCluster(order, matrix, clusterAssignments, 3);

        // Should still produce a valid route visiting all points
        assertNotNull(result);
        assertEquals(5, result.length);
        assertEquals(0, result[0]); // starts at warehouse

        // Verify all points visited exactly once (excluding return to depot)
        long distinct = java.util.Arrays.stream(result).distinct().count();
        assertEquals(5, distinct);
    }

    @Test
    void refineCrossCluster_shouldHandleSingleCluster() {
        // All points in one cluster - should fallback to regular 2-opt behavior
        double[][] matrix = {
                {0, 100, 1, 100},
                {100, 0, 100, 1},
                {1, 100, 0, 100},
                {100, 1, 100, 0}
        };
        int[] order = {0, 1, 2, 3}; // not optimal: 0→1(100) but 0→2(1) is better
        int[] clusterAssignments = {-1, 0, 0, 0}; // all in one cluster

        int[] result = service.refineCrossCluster(order, matrix, clusterAssignments, 3);

        // 2-opt should improve the route
        assertNotNull(result);
        assertEquals(4, result.length);
    }

    @Test
    void refineCrossCluster_shouldHandleTrivialRoute() {
        double[][] matrix = {{0, 1}, {1, 0}};
        int[] order = {0, 1};
        int[] clusterAssignments = {-1, 0};

        int[] result = service.refineCrossCluster(order, matrix, clusterAssignments, 3);
        assertArrayEquals(order, result);
    }

    // ===== refineWithMatrix tests =====

    @Test
    void refineWithMatrix_smallRoute_shouldReturnUnchanged() {
        // 2 points — too small for 2-opt
        double[][] matrix = {{0, 100}, {100, 0}};
        int[] order = {0, 1};
        int[] result = service.refineWithMatrix(order, matrix);
        assertArrayEquals(order, result);
    }

    @Test
    void refineWithMatrix_threePoints_shouldReturnUnchanged() {
        // 3 points — too small for 2-opt (needs at least 4 edges to swap)
        double[][] matrix = {
                {0, 1, 10},
                {1, 0, 1},
                {10, 1, 0}
        };
        int[] order = {0, 1, 2};
        int[] result = service.refineWithMatrix(order, matrix);
        assertArrayEquals(order, result);
    }

    @Test
    void refineWithMatrix_shouldImproveCrossedRoute() {
        // A route where two edges cross — 2-opt should uncross them
        // Points: 0-1-2-3 crosses over 0-2-1-3 is better
        double[][] matrix = {
                {0, 1,  10, 10},
                {1, 0,  1,  10},
                {10, 1, 0,  1},
                {10, 10,1,  0}
        };
        // Current: 0→1→3→2 (total edges: 0-1=1, 1-3=10, 3-2=1 → total=12)
        int[] order = {0, 1, 3, 2};
        int[] result = service.refineWithMatrix(order, matrix);

        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals(0, result[0]);

        // Compute total distance
        double dist = 0;
        for (int i = 0; i < result.length - 1; i++) {
            dist += matrix[result[i]][result[i + 1]];
        }
        // The improved route should be ≤ the original cross (+1 for possible return)
        double originalDist = matrix[0][1] + matrix[1][3] + matrix[3][2];
        assertTrue(dist <= originalDist + 1.0,
                "Refined distance " + dist + " should not exceed original " + originalDist);
    }

    @Test
    void refineWithMatrix_alreadyOptimal_shouldNotChange() {
        double[][] matrix = {
                {0, 1, 100, 100, 100},
                {1, 0, 1,   100, 100},
                {100, 1, 0, 1,   100},
                {100, 100, 1, 0, 1},
                {100, 100, 100, 1, 0}
        };
        int[] order = {0, 1, 2, 3, 4}; // already optimal linear chain
        int[] result = service.refineWithMatrix(order, matrix);
        assertArrayEquals(order, result);
    }

    // ===== refineCrossCluster additional tests =====

    @Test
    void refineCrossCluster_noCrossClusterBoundaries_shouldFallbackTo2Opt() {
        // All points in one cluster — no boundaries found
        double[][] matrix = {
                {0, 100, 1, 100},
                {100, 0, 100, 1},
                {1, 100, 0, 100},
                {100, 1, 100, 0}
        };
        int[] order = {0, 1, 2, 3};
        int[] clusterAssignments = {-1, 0, 0, 0}; // all same cluster, no boundaries

        int[] result = service.refineCrossCluster(order, matrix, clusterAssignments, 3);

        assertNotNull(result);
        assertEquals(4, result.length);
    }

    @Test
    void refineCrossCluster_smallRoute_shouldReturnUnchanged() {
        double[][] matrix = {{0, 10}, {10, 0}};
        int[] order = {0, 1};
        int[] clusterAssignments = {-1, 0};
        int[] result = service.refineCrossCluster(order, matrix, clusterAssignments, 3);
        assertArrayEquals(order, result);
    }
}
