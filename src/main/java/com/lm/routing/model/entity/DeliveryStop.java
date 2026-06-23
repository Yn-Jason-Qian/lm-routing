package com.lm.routing.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "delivery_stops")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryStop {

    @Id
    @Column(name = "stop_id")
    private String stopId;

    @Column(name = "name")
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "lat", nullable = false)
    private Double lat;

    @Column(name = "lng", nullable = false)
    private Double lng;

    /**
     * Position in the optimized route (0 = first stop after warehouse, warehouse is always 0).
     * Set after TSP solving completes.
     */
    @Column(name = "seq")
    private Integer seq;

    // === Extension fields for future VRP / time-window support ===

    @Column(name = "time_window_start")
    private Instant timeWindowStart;

    @Column(name = "time_window_end")
    private Instant timeWindowEnd;

    @Column(name = "weight_kg")
    private Double weightKg;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_plan_id")
    @ToString.Exclude
    private RoutePlan routePlan;
}
