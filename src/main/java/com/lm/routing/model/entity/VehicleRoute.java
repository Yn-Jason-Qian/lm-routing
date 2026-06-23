package com.lm.routing.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * A single vehicle's route in a multi-vehicle VRP solution.
 *
 * Each VehicleRoute represents one vehicle's complete delivery sequence —
 * from warehouse to final stop (and optionally back to warehouse).
 * The {@link RouteResult} aggregates all vehicle routes for a plan.
 */
@Entity
@Table(name = "vehicle_routes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Vehicle number within the plan (0-based). */
    @Column(name = "vehicle_index", nullable = false)
    private int vehicleIndex;

    /** Total real road distance for this vehicle (meters). */
    @Column(name = "total_distance_meters")
    private Long totalDistanceMeters;

    /** Total driving duration for this vehicle (seconds). */
    @Column(name = "total_duration_seconds")
    private Long totalDurationSeconds;

    @OneToMany(mappedBy = "vehicleRoute", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    @Builder.Default
    @ToString.Exclude
    private List<RouteSegment> segments = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_id")
    @ToString.Exclude
    private RouteResult routeResult;
}
