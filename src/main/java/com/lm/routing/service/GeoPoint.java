package com.lm.routing.service;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Immutable representation of a geographic coordinate.
 */
@Data
@AllArgsConstructor
public class GeoPoint {
    private final double lat;
    private final double lng;
}
