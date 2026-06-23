package com.lm.routing.service.provider.mapbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.routing.service.GeoPoint;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wraps the Mapbox Directions Matrix API.
 *
 * Provides real road distance matrices at ~$0.001/element.
 * Max 25 coordinates per request.
 *
 * Endpoint: GET /directions-matrix/v1/mapbox/driving/{coordinates}?annotations=distance
 * Auth: access_token query parameter
 */
@Slf4j
@Service
public class MapboxApiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routing.mapbox.access-token:}")
    private String accessToken;

    @Value("${routing.mapbox.base-url:https://api.mapbox.com}")
    private String baseUrl;

    @Value("${routing.traffic.enabled:false}")
    private boolean trafficEnabled;

    private static final int MAX_COORDS_PER_REQUEST = 25;

    public MapboxApiService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Build a full N×N distance matrix via Mapbox Matrix API.
     *
     * @param points list of geographic points
     * @return N×N distance matrix in meters, or null on failure
     */
    public double[][] buildMatrix(List<GeoPoint> points) {
        int n = points.size();
        if (n < 2) {
            return new double[][]{{0}};
        }

        if (n <= MAX_COORDS_PER_REQUEST) {
            return requestMatrix(points);
        }

        // Chunk large matrices into 25×25 blocks
        return buildLargeMatrix(points);
    }

    /**
     * Request a distance matrix for up to 25 points.
     */
    private double[][] requestMatrix(List<GeoPoint> points) {
        String coords = points.stream()
                .map(p -> p.getLng() + "," + p.getLat())
                .collect(Collectors.joining(";"));

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/directions-matrix/v1/mapbox/driving/" + coords)
                .queryParam("annotations", "distance")
                .queryParam("access_token", accessToken);

        if (trafficEnabled) {
            builder.queryParam("depart_at", "now");
        }

        String url = builder.toUriString();

        try {
            log.debug("Mapbox Matrix: {} points", points.size());
            String responseJson = restTemplate.getForObject(url, String.class);
            MapboxMatrixResponse response = objectMapper.readValue(responseJson, MapboxMatrixResponse.class);

            if (response == null || !"Ok".equals(response.getCode())) {
                String errorMsg = response != null ? response.getCode() : "null";
                log.warn("Mapbox Matrix API error: {}", errorMsg);
                return null;
            }

            return parseDistances(response, points.size());

        } catch (Exception e) {
            log.error("Mapbox Matrix API call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build a large matrix by chunking into blocks of 25.
     */
    private double[][] buildLargeMatrix(List<GeoPoint> points) {
        int n = points.size();
        double[][] matrix = new double[n][n];

        for (int rowStart = 0; rowStart < n; rowStart += MAX_COORDS_PER_REQUEST) {
            int rowEnd = Math.min(rowStart + MAX_COORDS_PER_REQUEST, n);
            List<GeoPoint> srcPoints = points.subList(rowStart, rowEnd);

            for (int colStart = 0; colStart < n; colStart += MAX_COORDS_PER_REQUEST) {
                int colEnd = Math.min(colStart + MAX_COORDS_PER_REQUEST, n);
                List<GeoPoint> dstPoints = points.subList(colStart, colEnd);

                // Build combined coordinate list: sources + destinations
                String coords = srcPoints.stream()
                        .map(p -> p.getLng() + "," + p.getLat())
                        .collect(Collectors.joining(";"));
                if (!dstPoints.equals(srcPoints)) {
                    coords += ";" + dstPoints.stream()
                            .map(p -> p.getLng() + "," + p.getLat())
                            .collect(Collectors.joining(";"));
                }

                // Build sources and destinations indices
                String sources = java.util.stream.IntStream.range(0, srcPoints.size())
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining(";"));
                String destinations = java.util.stream.IntStream
                        .range(srcPoints.size(), srcPoints.size() + dstPoints.size())
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining(";"));

                UriComponentsBuilder largeBuilder = UriComponentsBuilder
                        .fromHttpUrl(baseUrl + "/directions-matrix/v1/mapbox/driving/" + coords)
                        .queryParam("annotations", "distance")
                        .queryParam("sources", sources)
                        .queryParam("destinations", destinations)
                        .queryParam("access_token", accessToken);

                if (trafficEnabled) {
                    largeBuilder.queryParam("depart_at", "now");
                }

                String url = largeBuilder.toUriString();

                try {
                    String responseJson = restTemplate.getForObject(url, String.class);
                    MapboxMatrixResponse response = objectMapper.readValue(responseJson, MapboxMatrixResponse.class);

                    if (response != null && "Ok".equals(response.getCode()) && response.getDistances() != null) {
                        double[][] sub = response.getDistancesAsDouble();
                        for (int i = 0; i < sub.length && i < srcPoints.size(); i++) {
                            for (int j = 0; j < sub[i].length && j < dstPoints.size(); j++) {
                                matrix[rowStart + i][colStart + j] = sub[i][j];
                            }
                        }
                    }

                    // Rate limiting
                    Thread.sleep(100);
                } catch (Exception e) {
                    log.error("Mapbox chunk [{},{}] failed: {}", rowStart, colStart, e.getMessage());
                }
            }
        }

        return matrix;
    }

    private double[][] parseDistances(MapboxMatrixResponse response, int n) {
        double[][] matrix = new double[n][n];
        if (response.getDistances() == null) return matrix;

        for (int i = 0; i < Math.min(n, response.getDistances().size()); i++) {
            List<Double> row = response.getDistances().get(i);
            for (int j = 0; j < Math.min(n, row.size()); j++) {
                matrix[i][j] = row.get(j) != null ? row.get(j) : 0.0;
            }
        }
        return matrix;
    }

    // ===== Mapbox Matrix API response DTOs =====

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapboxMatrixResponse {
        private String code;
        private List<List<Double>> distances;
        private List<List<Double>> durations;

        @JsonProperty("source_points")
        private List<MapboxWaypoint> sourcePoints;

        @JsonProperty("destination_points")
        private List<MapboxWaypoint> destinationPoints;

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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapboxWaypoint {
        private double[] location; // [lng, lat]
        private String name;
    }
}
