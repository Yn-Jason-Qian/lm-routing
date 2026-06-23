package com.lm.routing.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wraps the Google Maps Distance Matrix API.
 *
 * Cost model: charged per ELEMENT (origin × destination).
 * For 200 points, a full matrix costs 40,000 elements ≈ $200.
 * MUST be paired with clustering to reduce costs by 90%+.
 *
 * Endpoint: /maps/api/distancematrix/json
 * Max: 25 origins × 25 destinations per request.
 */
@Slf4j
@Service
public class GoogleMapsApiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routing.google.api-key:}")
    private String apiKey;

    @Value("${routing.google.base-url:https://maps.googleapis.com}")
    private String baseUrl;

    private static final int MAX_ELEMENTS = 100; // Max origins + destinations per call

    public GoogleMapsApiService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Compute a distance sub-matrix for a set of origin points to a set
     * of destination points. Returns [origins.length][destinations.length].
     *
     * @param origins      origin points
     * @param destinations destination points
     * @return distance matrix [origins][destinations] in meters, or null on failure
     */
    public double[][] computeSubMatrix(List<GeoPoint> origins, List<GeoPoint> destinations) {
        if (!isAvailable()) {
            log.debug("Google Maps API key not configured");
            return null;
        }

        int origSize = origins.size();
        int destSize = destinations.size();

        // Google Matrix API: max 25 origins or 25 destinations,
        // and total elements should be kept reasonable
        if (origSize <= 25 && destSize <= 25) {
            return requestMatrix(origins, destinations);
        }

        // Chunked: process in blocks if needed
        double[][] result = new double[origSize][destSize];
        int chunkSize = 25;

        for (int i = 0; i < origSize; i += chunkSize) {
            int iEnd = Math.min(i + chunkSize, origSize);
            for (int j = 0; j < destSize; j += chunkSize) {
                int jEnd = Math.min(j + chunkSize, destSize);
                double[][] sub = requestMatrix(
                        origins.subList(i, iEnd),
                        destinations.subList(j, jEnd));
                if (sub != null) {
                    for (int si = 0; si < sub.length; si++) {
                        for (int sj = 0; sj < sub[si].length; sj++) {
                            result[i + si][j + sj] = sub[si][sj];
                        }
                    }
                }
            }
        }
        return result;
    }

    private double[][] requestMatrix(List<GeoPoint> origins, List<GeoPoint> destinations) {
        String origStr = origins.stream()
                .map(p -> p.getLat() + "," + p.getLng())
                .collect(Collectors.joining("|"));
        String destStr = destinations.stream()
                .map(p -> p.getLat() + "," + p.getLng())
                .collect(Collectors.joining("|"));

        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/maps/api/distancematrix/json")
                .queryParam("origins", origStr)
                .queryParam("destinations", destStr)
                .queryParam("mode", "driving")
                .queryParam("key", apiKey)
                .toUriString();

        try {
            log.debug("Google Matrix: {}×{}", origins.size(), destinations.size());
            String responseJson = restTemplate.getForObject(url, String.class);

            GmDistanceMatrixResponse resp = objectMapper.readValue(
                    responseJson, GmDistanceMatrixResponse.class);

            if (resp == null || !"OK".equals(resp.getStatus())) {
                String errorMsg = resp != null ? resp.getStatus() : "null";
                log.warn("Google Matrix API error: {}", errorMsg);
                return null;
            }

            return parseDistances(resp, origins.size(), destinations.size());

        } catch (Exception e) {
            log.error("Google Matrix API call failed: {}", e.getMessage());
            return null;
        }
    }

    private double[][] parseDistances(GmDistanceMatrixResponse resp,
                                       int rows, int cols) {
        double[][] matrix = new double[rows][cols];
        if (resp.getRows() == null) return matrix;

        for (int i = 0; i < Math.min(rows, resp.getRows().size()); i++) {
            GmRow row = resp.getRows().get(i);
            if (row.getElements() == null) continue;
            for (int j = 0; j < Math.min(cols, row.getElements().size()); j++) {
                GmElement el = row.getElements().get(j);
                if ("OK".equals(el.getStatus()) && el.getDistance() != null) {
                    matrix[i][j] = el.getDistance().getValue(); // meters
                }
            }
        }
        return matrix;
    }

    // ===== Google Maps Distance Matrix Response DTOs =====

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GmDistanceMatrixResponse {
        private String status;
        @JsonProperty("origin_addresses")
        private List<String> originAddresses;
        @JsonProperty("destination_addresses")
        private List<String> destinationAddresses;
        private List<GmRow> rows;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GmRow {
        private List<GmElement> elements;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GmElement {
        private String status;
        private GmDistance distance;
        private GmDuration duration;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GmDistance {
        private long value;   // meters
        private String text;  // human-readable
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GmDuration {
        private long value;   // seconds
        private String text;
    }
}
