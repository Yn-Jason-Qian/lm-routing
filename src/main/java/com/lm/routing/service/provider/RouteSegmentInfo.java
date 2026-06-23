package com.lm.routing.service.provider;

import lombok.Data;

/**
 * A single segment of the driving route between two consecutive stops.
 * Shared across all enrichment providers (AMap, AWS, etc.).
 */
@Data
public class RouteSegmentInfo {
    private int seq;
    private String fromStopId;
    private String toStopId;
    private double fromLat, fromLng;
    private double toLat, toLng;
    private long distanceMeters;
    private long durationSeconds;
    private String polyline;
}
