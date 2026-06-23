package com.lm.routing.service.provider.bing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.routing.service.GeoPoint;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wraps the Bing Maps Distance Matrix API.
 *
 * Provides real road distance matrices at ~$0.002/element.
 * Supports up to 700 cells per synchronous request.
 * Truck routing attributes available.
 *
 * Endpoint: POST /REST/v1/Routes/DistanceMatrix?key={key}
 * Auth: API key query parameter
 *
 * @see <a href="https://learn.microsoft.com/en-us/bingmaps/rest-services/routes/calculate-a-distance-matrix">Bing Maps Distance Matrix docs</a>
 */
@Slf4j
@Service
public class BingMapsApiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routing.bing.api-key:}")
    private String apiKey;

    @Value("${routing.bing.base-url:https://dev.virtualearth.net}")
    private String baseUrl;

    /**
     * Bing Maps allows ~700 matrix cells per synchronous request.
     * We use conservative limits: max 30 origins × 30 destinations.
     */
    private static final int MAX_ORIGINS = 30;
    private static final int MAX_DESTINATIONS = 30;

    public BingMapsApiService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Build a full N×N distance matrix via Bing Maps Distance Matrix API.
     *
     * @param points list of geographic points
     * @return N×N distance matrix in meters, or null on failure
     */
    public double[][] buildMatrix(List<GeoPoint> points) {
        int n = points.size();
        if (n < 2) {
            return new double[][]{{0}};
        }

        if (n <= MAX_ORIGINS) {
            return requestMatrix(points, points);
        }

        // Chunk large matrices
        return buildLargeMatrix(points);
    }

    /**
     * Request a distance sub-matrix for origins × destinations.
     */
    private double[][] requestMatrix(List<GeoPoint> origins, List<GeoPoint> destinations) {
        String url = baseUrl + "/REST/v1/Routes/DistanceMatrix?key=" + apiKey;

        try {
            BingMatrixRequest body = new BingMatrixRequest();
            body.setOrigins(toCoordinateList(origins));
            body.setDestinations(toCoordinateList(destinations));
            body.setTravelMode("driving");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<BingMatrixRequest> request = new HttpEntity<>(body, headers);

            log.debug("Bing Matrix: {}×{}", origins.size(), destinations.size());
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class);

            BingMatrixResponse resp = objectMapper.readValue(
                    response.getBody(), BingMatrixResponse.class);

            if (resp == null || resp.getResourceSets() == null || resp.getResourceSets().isEmpty()) {
                log.warn("Bing Matrix API returned empty response");
                return null;
            }

            BingResourceSet rs = resp.getResourceSets().get(0);
            if (rs.getResources() == null || rs.getResources().isEmpty()) {
                log.warn("Bing Matrix API: no resources — estimatedTotal={}", rs.getEstimatedTotal());
                return null;
            }

            BingDistanceMatrix matrix = rs.getResources().get(0);
            return parseDistances(matrix, origins.size(), destinations.size());

        } catch (Exception e) {
            log.error("Bing Matrix API call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build a large matrix by chunking.
     */
    private double[][] buildLargeMatrix(List<GeoPoint> points) {
        int n = points.size();
        double[][] matrix = new double[n][n];

        for (int rowStart = 0; rowStart < n; rowStart += MAX_ORIGINS) {
            int rowEnd = Math.min(rowStart + MAX_ORIGINS, n);
            List<GeoPoint> srcPoints = points.subList(rowStart, rowEnd);

            for (int colStart = 0; colStart < n; colStart += MAX_DESTINATIONS) {
                int colEnd = Math.min(colStart + MAX_DESTINATIONS, n);
                List<GeoPoint> dstPoints = points.subList(colStart, colEnd);

                double[][] sub = requestMatrix(srcPoints, dstPoints);
                if (sub != null) {
                    for (int i = 0; i < sub.length; i++) {
                        for (int j = 0; j < sub[i].length; j++) {
                            matrix[rowStart + i][colStart + j] = sub[i][j];
                        }
                    }
                }

                // Rate limiting
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }

        return matrix;
    }

    private List<BingCoordinate> toCoordinateList(List<GeoPoint> points) {
        return points.stream()
                .map(p -> new BingCoordinate(p.getLat(), p.getLng()))
                .collect(Collectors.toList());
    }

    private double[][] parseDistances(BingDistanceMatrix matrix, int rows, int cols) {
        double[][] result = new double[rows][cols];
        if (matrix.getResults() == null) return result;

        for (BingMatrixEntry entry : matrix.getResults()) {
            int i = entry.getOriginIndex();
            int j = entry.getDestinationIndex();
            if (i >= 0 && i < rows && j >= 0 && j < cols) {
                if (entry.getTravelDistance() > 0) {
                    result[i][j] = entry.getTravelDistance() * 1000.0; // km → meters
                }
            }
        }
        return result;
    }

    // ===== Bing Maps API DTOs =====

    @Data
    public static class BingMatrixRequest {
        private List<BingCoordinate> origins;
        private List<BingCoordinate> destinations;
        private String travelMode; // "driving" or "truck"
    }

    @Data
    public static class BingCoordinate {
        private final double latitude;
        private final double longitude;

        public BingCoordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BingMatrixResponse {
        private List<BingResourceSet> resourceSets;
        private int statusCode;
        private String statusDescription;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BingResourceSet {
        private int estimatedTotal;
        private List<BingDistanceMatrix> resources;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BingDistanceMatrix {
        private List<BingMatrixEntry> results;
        private List<BingCoordinateInfo> origins;
        private List<BingCoordinateInfo> destinations;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BingMatrixEntry {
        @JsonProperty("originIndex")
        private int originIndex;

        @JsonProperty("destinationIndex")
        private int destinationIndex;

        private double travelDistance;  // kilometers
        private double travelDuration;  // seconds
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BingCoordinateInfo {
        private double latitude;
        private double longitude;
    }
}
