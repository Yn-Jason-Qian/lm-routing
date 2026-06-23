package com.lm.routing.exception;

/**
 * Base exception for route planning domain errors.
 */
public class RoutePlanException extends RuntimeException {

    public RoutePlanException(String message) {
        super(message);
    }

    public RoutePlanException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown when a route plan with the given ID is not found.
     */
    public static class NotFoundException extends RoutePlanException {
        public NotFoundException(String planId) {
            super("Route plan not found: " + planId);
        }
    }

    /**
     * Thrown when the input data is invalid (e.g., over API limits, bad coordinates).
     */
    public static class InvalidInputException extends RoutePlanException {
        public InvalidInputException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when the TSP solver fails unexpectedly.
     */
    public static class SolverException extends RoutePlanException {
        public SolverException(String message, Throwable cause) {
            super("Solver error: " + message, cause);
        }
    }
}
