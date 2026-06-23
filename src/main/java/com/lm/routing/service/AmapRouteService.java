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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the AMap (高德地图) Driving Direction API v3.
 *
 * Uses waypoints to batch up to 30 intermediate stops per call.
 * The route returned includes real road distances, durations, and polylines
 * for each segment.
 */
@Slf4j
@Service
public class AmapRouteService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routing.amap.key:}")
    private String apiKey;

    @Value("${routing.amap.base-url:https://restapi.amap.com}")
    private String baseUrl;

    @Value("${routing.amap.max-waypoints-per-call:30}")
    private int maxWaypointsPerCall;

    public AmapRouteService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * A single segment of the driving route between two consecutive waypoints.
     */
    @Data
    public static class RouteSegmentInfo {
        private int seq;
        private String fromStopId;
        private String toStopId;
        private double fromLat, fromLng;
        private double toLat, toLng;
        private long distanceMeters;
        private long durationSeconds;
        private String polyline;
    }

    /**
     * Fetch real road route segments for a batch of waypoints.
     *
     * @param originLat  origin latitude
     * @param originLng  origin longitude
     * @param destLat    destination latitude (may be same as origin for round-trip)
     * @param destLng    destination longitude
     * @param waypoints  list of intermediate waypoint coordinates [lat,lng,lat,lng,...]
     * @return list of segment info between consecutive points, or empty list on failure
     */
    public List<RouteSegmentInfo> fetchRouteSegments(
            double originLat, double originLng,
            double destLat, double destLng,
            List<Double> waypoints) {

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("AMap API key not configured. Route API calls will be skipped.");
            return Collections.emptyList();
        }

        try {
            String url = buildUrl(originLat, originLng, destLat, destLng, waypoints);
            log.debug("Calling AMap direction API: {} waypoints", waypoints.size() / 2);

            String responseJson = restTemplate.getForObject(url, String.class);
            AmapDirectionResponse response = objectMapper.readValue(responseJson, AmapDirectionResponse.class);

            if (response == null || response.getStatus() != 1) {
                String info = response != null ? response.getInfo() : "null response";
                log.warn("AMap API error: {}", info);
                return Collections.emptyList();
            }

            return parseSegments(response);

        } catch (Exception e) {
            log.error("AMap API call failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Build the AMap direction API URL.
     */
    private String buildUrl(double originLat, double originLng,
                            double destLat, double destLng,
                            List<Double> waypoints) {

        String origin = originLng + "," + originLat;
        String destination = destLng + "," + destLat;

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/v3/direction/driving")
                .queryParam("key", apiKey)
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("strategy", "0")      // 0 = fastest route (default)
                .queryParam("extensions", "all");  // return polyline + steps

        if (waypoints != null && !waypoints.isEmpty()) {
            StringBuilder wp = new StringBuilder();
            for (int i = 0; i < waypoints.size(); i += 2) {
                if (i > 0) wp.append(";");
                wp.append(waypoints.get(i + 1))  // lng first
                  .append(",")
                  .append(waypoints.get(i));      // lat second
            }
            builder.queryParam("waypoints", wp.toString());
        }

        return builder.toUriString();
    }

    /**
     * Parse AMap response into segment list.
     * AMap returns steps within each path segment; we extract the per-waypoint segments.
     */
    private List<RouteSegmentInfo> parseSegments(AmapDirectionResponse response) {
        List<RouteSegmentInfo> segments = new ArrayList<>();

        if (response.getRoute() == null
                || response.getRoute().getPaths() == null
                || response.getRoute().getPaths().isEmpty()) {
            return segments;
        }

        AmapPath path = response.getRoute().getPaths().get(0);
        List<AmapStep> steps = path.getSteps();

        if (steps == null || steps.isEmpty()) {
            return segments;
        }

        int seq = 0;
        for (AmapStep step : steps) {
            RouteSegmentInfo seg = new RouteSegmentInfo();
            seg.setSeq(seq++);
            seg.setDistanceMeters(Long.parseLong(step.getDistance()));
            seg.setDurationSeconds(Long.parseLong(step.getDuration()));
            seg.setPolyline(step.getPolyline());

            // Parse coordinates from the polyline or step info
            // The step's start/end are encoded in the polyline
            if (step.getPolyline() != null && !step.getPolyline().isEmpty()) {
                double[] coords = decodePolylineFirstLast(step.getPolyline());
                seg.setFromLng(coords[0]);
                seg.setFromLat(coords[1]);
                seg.setToLng(coords[2]);
                seg.setToLat(coords[3]);
            }

            segments.add(seg);
        }

        return segments;
    }

    /**
     * Decode first and last coordinate from an AMap polyline string.
     * AMap polyline is encoded as: lng1,lat1;lng2,lat2;...
     * Returns [fromLng, fromLat, toLng, toLat].
     */
    private double[] decodePolylineFirstLast(String polyline) {
        String[] points = polyline.split(";");
        if (points.length == 0) {
            return new double[]{0, 0, 0, 0};
        }
        String[] first = points[0].split(",");
        String[] last = points[points.length - 1].split(",");

        return new double[]{
                Double.parseDouble(first[0]), Double.parseDouble(first[1]),
                Double.parseDouble(last[0]), Double.parseDouble(last[1])
        };
    }

    // ===== AMap API JSON response DTOs =====

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AmapDirectionResponse {
        private int status;
        private String info;
        @JsonProperty("route")
        private AmapRoute route;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AmapRoute {
        private List<AmapPath> paths;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AmapPath {
        private String distance;
        private String duration;
        private List<AmapStep> steps;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AmapStep {
        private String distance;
        private String duration;
        private String polyline;
        private String instruction;
    }
}
