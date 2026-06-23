package com.lm.routing.model.entity;

import com.lm.routing.model.enums.PlanStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "route_plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutePlan {

    @Id
    @Column(name = "plan_id")
    private String planId;

    @Column(name = "warehouse_id")
    private String warehouseId;

    @Column(name = "warehouse_name")
    private String warehouseName;

    @Column(name = "warehouse_lat", nullable = false)
    private Double warehouseLat;

    @Column(name = "warehouse_lng", nullable = false)
    private Double warehouseLng;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private PlanStatus status = PlanStatus.PENDING;

    @Column(name = "status_message")
    private String statusMessage;

    @Column(name = "progress")
    @Builder.Default
    private Integer progress = 0;

    @OneToMany(mappedBy = "routePlan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    private List<DeliveryStop> stops = new ArrayList<>();

    @OneToOne(mappedBy = "routePlan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @ToString.Exclude
    private RouteResult result;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    // === Extension fields for future VRP support ===

    @Column(name = "vehicle_count")
    @Builder.Default
    private Integer vehicleCount = 1;

    @Column(name = "max_capacity_kg")
    private Double maxCapacityKg;

    @Column(name = "return_to_warehouse")
    @Builder.Default
    private Boolean returnToWarehouse = false;

    @Column(name = "avoid_tolls")
    @Builder.Default
    private Boolean avoidTolls = false;

    // === Helper methods ===

    public void updateProgress(PlanStatus status, String message, int progress) {
        this.status = status;
        this.statusMessage = message;
        this.progress = progress;
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.progress = 100;
    }

    public void markFailed(String errorMessage) {
        this.status = PlanStatus.FAILED;
        this.statusMessage = errorMessage;
        this.completedAt = Instant.now();
    }
}
