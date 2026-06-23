package com.lm.routing.model.enums;

/**
 * Route planning lifecycle state machine:
 * PENDING → BUILDING_MATRIX → SOLVING_TSP → REFINING → COMPLETED
 *                                                   ↘ FAILED (from any state)
 */
public enum PlanStatus {
    PENDING,           // Request accepted, queued
    BUILDING_MATRIX,   // Computing Haversine distance matrix
    SOLVING_TSP,       // OR-Tools solving TSP
    REFINING,          // Fetching real road distances + 2-opt refinement
    COMPLETED,         // Route plan ready
    FAILED             // Error occurred
}
