package com.lm.routing.service.provider.aws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.routing.service.provider.RouteSegmentInfo;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wraps the AWS Location Service Route Calculator API.
 *
 * AWS does not provide a native Distance Matrix API. Instead, this service
 * follows the same pattern as AMap: Haversine matrix → TSP → route enrichment.
 *
 * Cost: ~$0.0005 per route calculation call — the cheapest map API option.
 *
 * Endpoint: POST /routes/v2/routes?key={api_key}
 * Auth: API key (simpler than SigV4 for server-side use)
 *
 * @see <a href="https://docs.aws.amazon.com/location/latest/APIReference/API_CalculateRoute.html">AWS Route Calculator docs</a>
 */
@Slf4j
@Service
public class AwsMapsService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${routing.aws.api-key:}")
    private String apiKey;

    @Value("${routing.aws.region:us-east-1}")
    private String region;

    @Value("${routing.aws.route-calculator:Default}")
    private String routeCalculator;

    private static final int MAX_WAYPOINTS = 25;

    public AwsMapsService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Fetch route segments between consecutive waypoints using AWS Route Calculator.
     *
     * @param originLat  origin latitude
     * @param originLng  origin longitude
     * @param destLat    destination latitude
     * @param destLng    destination longitude
     * @param waypoints  list of intermediate waypoint coordinates [lat, lng, lat, lng, ...]
     * @return list of segment info between consecutive points, or empty list on failure
     */
    public List<RouteSegmentInfo> fetchRouteSegments(
            double originLat, double originLng,
            double destLat, double destLng,
            List<Double> waypoints) {

        if (!isAvailable()) {
            log.warn("AWS Maps API key not configured");
            return Collections.emptyList();
        }

        try {
            AwsRouteRequest body = new AwsRouteRequest();
            body.setOrigin(List.of(originLng, originLat));  // AWS uses [lng, lat]
            body.setDestination(List.of(destLng, destLat));
            body.setTravelMode("Car");
            body.setDepartNow(true);

            // Add waypoints (AWS uses [lng, lat] per waypoint)
            if (waypoints != null && !waypoints.isEmpty()) {
                List<List<Double>> wpPositions = new ArrayList<>();
                for (int i = 0; i < waypoints.size(); i += 2) {
                    wpPositions.add(List.of(waypoints.get(i + 1), waypoints.get(i))); // lng, lat
                }
                body.setWaypointPositions(wpPositions);
            }

            String url = "https://routes.geo." + region + ".amazonaws.com/v2/routes"
                    + "?calculatorName=" + routeCalculator
                    + "&key=" + apiKey;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<AwsRouteRequest> request = new HttpEntity<>(body, headers);

            log.debug("AWS Route Calculator: {} waypoints", waypoints != null ? waypoints.size() / 2 : 0);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class);

            AwsRouteResponse resp = objectMapper.readValue(response.getBody(), AwsRouteResponse.class);
            return parseSegments(resp);

        } catch (Exception e) {
            log.error("AWS Route Calculator call failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RouteSegmentInfo> parseSegments(AwsRouteResponse response) {
        List<RouteSegmentInfo> segments = new ArrayList<>();
        if (response == null || response.getLegs() == null) return segments;

        int seq = 0;
        for (AwsRouteLeg leg : response.getLegs()) {
            RouteSegmentInfo seg = new RouteSegmentInfo();
            seg.setSeq(seq++);
            seg.setDistanceMeters(Math.round(leg.getDistance()));
            seg.setDurationSeconds(Math.round(leg.getDurationSeconds()));
            seg.setPolyline(leg.getGeometry() != null ? leg.getGeometry().getPolyline() : null);

            // Coordinates from leg geometry or leg start/end
            if (leg.getGeometry() != null && leg.getGeometry().getPolyline() != null) {
                double[] coords = decodePolylineFirstLast(leg.getGeometry().getPolyline());
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
     * Decode first and last coordinates from a flexible polyline.
     * AWS uses flexible-polyline encoding.
     * For simplicity, we decode only the first and last points.
     */
    private double[] decodePolylineFirstLast(String polyline) {
        if (polyline == null || polyline.isEmpty()) {
            return new double[]{0, 0, 0, 0};
        }
        try {
            // AWS flexible polyline: decode first and last points
            List<double[]> points = decodeFlexiblePolyline(polyline);
            if (points.isEmpty()) return new double[]{0, 0, 0, 0};
            double[] first = points.get(0);
            double[] last = points.get(points.size() - 1);
            return new double[]{first[1], first[0], last[1], last[0]}; // [lng, lat, lng, lat]
        } catch (Exception e) {
            log.debug("Failed to decode AWS polyline: {}", e.getMessage());
            return new double[]{0, 0, 0, 0};
        }
    }

    /**
     * AWS flexible polyline decoder.
     *
     * The format uses 5-bit character encoding (0-63 mapped to
     * A-Za-z0-9_\-). The first character is a header. Each subsequent
     * value is a zigzag-encoded delta in units of 1e-5 degrees.
     *
     * @see <a href="https://github.com/heremaps/flexible-polyline">Flexible Polyline spec</a>
     */
    private List<double[]> decodeFlexiblePolyline(String encoded) {
        List<double[]> points = new ArrayList<>();
        if (encoded == null || encoded.length() < 2) return points;

        // Skip header character (stores encoding format info)
        int i = 1;
        double lat = 0, lng = 0;

        while (i < encoded.length()) {
            int latDelta = decodeValue(encoded, i);
            if (latDelta == Integer.MIN_VALUE) break;
            i = advanceIndex(encoded, i);

            int lngDelta = decodeValue(encoded, i);
            if (lngDelta == Integer.MIN_VALUE) break;
            i = advanceIndex(encoded, i);

            lat += latDelta / 1e5;
            lng += lngDelta / 1e5;
            points.add(new double[]{lat, lng});
        }
        return points;
    }

    /** Decode a single zigzag-encoded value from the string starting at index. */
    private int decodeValue(String s, int start) {
        int result = 0;
        int shift = 0;
        for (int i = start; i < s.length(); i++) {
            int code = charToValue(s.charAt(i));
            if (code < 0) return Integer.MIN_VALUE;
            result |= (code & 0x1f) << shift;
            if ((code & 0x20) == 0) {
                return (result >>> 1) ^ -(result & 1); // zigzag decode
            }
            shift += 5;
        }
        return Integer.MIN_VALUE;
    }

    /** Map a printable character to its 6-bit value (0-63). */
    private int charToValue(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '_') return 62;
        if (c == '-') return 63;
        return -1;
    }

    /** Move past the current encoded value to start of next. */
    private int advanceIndex(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            int code = charToValue(s.charAt(i));
            if (code < 0 || (code & 0x20) == 0) return i + 1;
        }
        return s.length();
    }

    // ===== AWS Route Calculator API DTOs =====

    @Data
    public static class AwsRouteRequest {
        private List<Double> origin;       // [lng, lat]
        private List<Double> destination;  // [lng, lat]
        private List<List<Double>> waypointPositions;
        private String travelMode;         // "Car" or "Truck"
        @JsonProperty("DepartNow")
        private boolean departNow;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwsRouteResponse {
        private AwsRouteSummary summary;
        private List<AwsRouteLeg> legs;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwsRouteSummary {
        private double distance;       // meters
        private double durationSeconds;
        private List<Double> bbox;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwsRouteLeg {
        private double distance;       // meters
        private double durationSeconds;
        private AwsRouteGeometry geometry;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwsRouteGeometry {
        private String polyline;       // flexible-polyline encoded
    }
}
