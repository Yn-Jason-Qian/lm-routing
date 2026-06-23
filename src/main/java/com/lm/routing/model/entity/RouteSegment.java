package com.lm.routing.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "route_segments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Order in the final route, starting from 0 */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "from_stop_id")
    private String fromStopId;

    @Column(name = "to_stop_id")
    private String toStopId;

    @Column(name = "from_lat")
    private Double fromLat;

    @Column(name = "from_lng")
    private Double fromLng;

    @Column(name = "to_lat")
    private Double toLat;

    @Column(name = "to_lng")
    private Double toLng;

    /** Real road distance in meters (from AMap API) */
    @Column(name = "distance_meters")
    private Long distanceMeters;

    /** Real driving duration in seconds (from AMap API) */
    @Column(name = "duration_seconds")
    private Long durationSeconds;

    /** Encoded polyline for map rendering (from AMap API) */
    @Column(name = "polyline", length = 10000)
    private String polyline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_result_id")
    @ToString.Exclude
    private RouteResult routeResult;
}
