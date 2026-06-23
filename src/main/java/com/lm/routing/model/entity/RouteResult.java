package com.lm.routing.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "route_results")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteResult {

    @Id
    @Column(name = "result_id")
    private String resultId;

    /** Total real road distance in meters */
    @Column(name = "total_distance_meters")
    private Long totalDistanceMeters;

    /** Total real driving duration in seconds */
    @Column(name = "total_duration_seconds")
    private Long totalDurationSeconds;

    /** Whether the result was computed with real road data or Haversine fallback */
    @Column(name = "fallback")
    @Builder.Default
    private Boolean fallback = false;

    @OneToMany(mappedBy = "routeResult", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    @Builder.Default
    @ToString.Exclude
    private List<RouteSegment> segments = new ArrayList<>();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_plan_id")
    @ToString.Exclude
    private RoutePlan routePlan;
}
