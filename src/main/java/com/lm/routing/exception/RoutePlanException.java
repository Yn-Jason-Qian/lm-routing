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
        private final String planId;

        public NotFoundException(String planId) {
            super("Route plan not found: " + planId);
            this.planId = planId;
        }

        public String getPlanId() {
            return planId;
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
        private final String reason;

        public SolverException(String reason, Throwable cause) {
            super("Solver error: " + reason, cause);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }
}
