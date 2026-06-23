package com.lm.routing.service;

import com.lm.routing.service.provider.MatrixProvider;
import com.lm.routing.service.provider.MatrixResult;
import com.lm.routing.service.provider.ProviderSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @deprecated Use {@link ProviderSelector} and {@link MatrixProvider} directly.
 * This class remains as a backward-compatible delegate that forwards to the
 * new strategy pattern infrastructure.
 */
@Slf4j
@Service
@Deprecated
public class DistanceMatrixProvider {

    private final ProviderSelector selector;
    private final HaversineMatrixService haversineService;

    public DistanceMatrixProvider(ProviderSelector selector,
                                   HaversineMatrixService haversineService) {
        this.selector = selector;
        this.haversineService = haversineService;
    }

    /**
     * @deprecated Use {@link ProviderSelector#selectProvider(List, GeoPoint)}
     *             and then call {@link MatrixProvider#buildMatrix(List, GeoPoint)}.
     */
    @Deprecated
    public MatrixResult buildMatrix(List<GeoPoint> points, GeoPoint warehouse) {
        log.debug("DistanceMatrixProvider.buildMatrix() called — delegating to ProviderSelector");
        MatrixProvider provider = selector.selectProvider(points, warehouse);
        return provider.buildMatrix(points, warehouse);
    }

    /**
     * @deprecated Use {@link com.lm.routing.service.provider.GoogleClusterHybridProvider#enrichBoundaryEdges}.
     */
    @Deprecated
    public int enrichBoundaryEdges(double[][] matrix, int[] tspOrder,
                                    List<GeoPoint> points, int[] clusterAssignments,
                                    List<KMeansClusteringService.Cluster> clusters,
                                    int candidateCount) {
        log.debug("DistanceMatrixProvider.enrichBoundaryEdges() called — delegating to GoogleClusterHybridProvider");
        MatrixProvider provider = selector.findByName("CLUSTER_HYBRID");
        if (provider instanceof com.lm.routing.service.provider.GoogleClusterHybridProvider gchp) {
            return gchp.enrichBoundaryEdges(matrix, tspOrder, points,
                    clusterAssignments, clusters, candidateCount);
        }
        log.warn("CLUSTER_HYBRID provider not available for boundary enrichment");
        return 0;
    }
}
