package com.lm.routing.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wraps the OSRM (Open Source Routing Machine) HTTP API.
 *
 * OSRM is a self-hosted routing engine based on OpenStreetMap data.
 * Deploy as a Docker sidecar — unlimited free distance matrices.
 *
 * Key endpoints:
 *   GET /table/v1/driving/{coordinates}?annotations=distance
 *
 * Coordinates format: lng1,lat1;lng2,lat2;...
 * Returns a full n×n distance matrix (in meters).
 */
@Slf4j
@Service
public class OsrmService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routing.osrm.base-url:http://localhost:5000}")
    private String baseUrl;

    @Value("${routing.osrm.timeout-seconds:60}")
    private int timeoutSeconds;

    public OsrmService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Check if OSRM is available.
     */
    public boolean isAvailable() {
        try {
            String url = baseUrl + "/table/v1/driving/0,0;0,0?sources=0&destinations=0";
            restTemplate.getForObject(url, String.class);
            return true;
        } catch (Exception e) {
            log.debug("OSRM not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Build a full n×n distance matrix via OSRM table API.
     *
     * @param points list of geographic points
     * @return n×n distance matrix in meters, or null on failure
     */
    public double[][] buildMatrix(List<GeoPoint> points) {
        int n = points.size();
        if (n < 2) {
            return new double[][]{{0}};
        }

        // OSRM /table has internal limits (~10,000 coordinate pairs).
        // For very large matrices, split and stitch.
        if (n <= 100) {
            return requestMatrix(points);
        }

        // Large matrix: chunk into 100-point blocks
        return buildLargeMatrix(points);
    }

    /**
     * Request a distance matrix from OSRM for up to ~100 points.
     */
    private double[][] requestMatrix(List<GeoPoint> points) {
        String coords = points.stream()
                .map(p -> p.getLng() + "," + p.getLat())
                .collect(Collectors.joining(";"));

        String url = baseUrl + "/table/v1/driving/" + coords
                + "?annotations=distance";

        try {
            log.debug("OSRM /table request: {} points", points.size());
            String responseJson = restTemplate.getForObject(url, String.class);

            OsrmTableResponse response = objectMapper.readValue(
                    responseJson, OsrmTableResponse.class);

            if (response == null || response.getDistances() == null) {
                log.warn("OSRM returned null response");
                return null;
            }

            return response.getDistancesAsDouble();

        } catch (Exception e) {
            log.error("OSRM request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build a large matrix by chunking into blocks of 100 points.
     */
    private double[][] buildLargeMatrix(List<GeoPoint> points) {
        int n = points.size();
        int chunkSize = 100;
        double[][] matrix = new double[n][n];

        for (int rowStart = 0; rowStart < n; rowStart += chunkSize) {
            int rowEnd = Math.min(rowStart + chunkSize, n);
            List<GeoPoint> srcPoints = points.subList(rowStart, rowEnd);

            for (int colStart = 0; colStart < n; colStart += chunkSize) {
                int colEnd = Math.min(colStart + chunkSize, n);
                List<GeoPoint> dstPoints = points.subList(colStart, colEnd);

                // Build coords: all sources, then all destinations
                List<String> allCoords = new ArrayList<>();
                for (GeoPoint p : srcPoints) allCoords.add(p.getLng() + "," + p.getLat());
                for (GeoPoint p : dstPoints) allCoords.add(p.getLng() + "," + p.getLat());

                String coords = String.join(";", allCoords);
                String sources = IntStream(0, srcPoints.size()).mapToObj(String::valueOf).collect(Collectors.joining(";"));
                String destinations = IntStream(srcPoints.size(), srcPoints.size() + dstPoints.size()).mapToObj(String::valueOf).collect(Collectors.joining(";"));

                String url = baseUrl + "/table/v1/driving/" + coords
                        + "?sources=" + sources
                        + "&destinations=" + destinations
                        + "&annotations=distance";

                try {
                    String responseJson = restTemplate.getForObject(url, String.class);
                    OsrmTableResponse response = objectMapper.readValue(responseJson, OsrmTableResponse.class);

                    if (response != null && response.getDistances() != null) {
                        double[][] subMatrix = response.getDistancesAsDouble();
                        for (int i = 0; i < subMatrix.length; i++) {
                            for (int j = 0; j < subMatrix[i].length; j++) {
                                matrix[rowStart + i][colStart + j] = subMatrix[i][j];
                            }
                        }
                    }

                    // Respect OSRM rate limits
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.error("OSRM chunk [{},{}] failed: {}", rowStart, colStart, e.getMessage());
                    // Fill with Haversine as fallback for this chunk
                    for (int i = 0; i < srcPoints.size(); i++) {
                        for (int j = 0; j < dstPoints.size(); j++) {
                            matrix[rowStart + i][colStart + j] =
                                    HaversineMatrixService.haversineDistance(
                                            srcPoints.get(i), dstPoints.get(j));
                        }
                    }
                }
            }
        }

        return matrix;
    }

    private java.util.stream.IntStream IntStream(int start, int end) {
        return java.util.stream.IntStream.range(start, end);
    }

    // ===== OSRM JSON response DTOs =====

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsrmTableResponse {
        private String code;
        private List<List<Double>> distances;

        public double[][] getDistancesAsDouble() {
            if (distances == null || distances.isEmpty()) return new double[0][0];
            int rows = distances.size();
            int cols = distances.get(0).size();
            double[][] result = new double[rows][cols];
            for (int i = 0; i < rows; i++) {
                List<Double> row = distances.get(i);
                for (int j = 0; j < cols; j++) {
                    result[i][j] = row.get(j) != null ? row.get(j) : 0.0;
                }
            }
            return result;
        }
    }
}
