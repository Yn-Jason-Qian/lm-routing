package com.lm.routing.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class RoutePlanRequest {

    @Valid
    @NotNull(message = "Warehouse is required")
    private WarehouseInfo warehouse;

    @Valid
    @NotEmpty(message = "At least one delivery stop is required")
    private List<StopInfo> stops;

    private RouteOptions options;

    // ===== Inner DTOs =====

    @Data
    public static class WarehouseInfo {
        @NotBlank(message = "Warehouse ID is required")
        private String id;

        private String name;

        @NotNull(message = "Warehouse latitude is required")
        @DecimalMin(value = "-90.0")
        @DecimalMax(value = "90.0")
        private Double lat;

        @NotNull(message = "Warehouse longitude is required")
        @DecimalMin(value = "-180.0")
        @DecimalMax(value = "180.0")
        private Double lng;
    }

    @Data
    public static class StopInfo {
        @NotBlank(message = "Stop ID is required")
        private String id;

        private String name;
        private String address;

        @NotNull(message = "Stop latitude is required")
        @DecimalMin(value = "-90.0")
        @DecimalMax(value = "90.0")
        private Double lat;

        @NotNull(message = "Stop longitude is required")
        @DecimalMin(value = "-180.0")
        @DecimalMax(value = "180.0")
        private Double lng;

        // Extension fields
        private String timeWindowStart;   // ISO-8601
        private String timeWindowEnd;     // ISO-8601
        private Double weightKg;
    }

    @Data
    public static class RouteOptions {
        private Boolean avoidTolls = false;
        private Boolean returnToWarehouse = false;
        private Integer maxSolveTimeSeconds = 30;
    }
}
