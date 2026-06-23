package com.lm.routing.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutePlanResponse {

    private String planId;
    private String status;
    private String statusMessage;
    private Integer progress;

    private WarehouseInfo warehouse;
    private List<StopInfo> stops;

    /** Single-vehicle route (backward compatible, populated when vehicleCount=1). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private RouteInfo route;

    /** Multi-vehicle routes (populated when vehicleCount > 1). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<VehicleRouteInfo> routes;

    private Instant createdAt;
    private Instant completedAt;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WarehouseInfo {
        private String id;
        private String name;
        private Double lat;
        private Double lng;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StopInfo {
        private String id;
        private String name;
        private String address;
        private Double lat;
        private Double lng;
        private Integer seq;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RouteInfo {
        private Long totalDistanceMeters;
        private Long totalDurationSeconds;
        private Boolean fallback;
        private List<SegmentInfo> segments;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SegmentInfo {
        private Integer seq;
        private String fromStopId;
        private String toStopId;
        private Double fromLat;
        private Double fromLng;
        private Double toLat;
        private Double toLng;
        private Long distanceMeters;
        private Long durationSeconds;
        private String polyline;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VehicleRouteInfo {
        private Integer vehicleIndex;
        private Long totalDistanceMeters;
        private Long totalDurationSeconds;
        private List<SegmentInfo> segments;
    }
}
