package com.lm.routing.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleNotFound_shouldReturn404() {
        RoutePlanException.NotFoundException ex =
                new RoutePlanException.NotFoundException("plan-123");

        ProblemDetail pd = handler.handleNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND.value(), pd.getStatus());
        assertEquals("Route Plan Not Found", pd.getTitle());
        assertTrue(pd.getDetail().contains("plan-123"));
        assertNotNull(pd.getType());
    }

    @Test
    void handleInvalidInput_shouldReturn400() {
        RoutePlanException.InvalidInputException ex =
                new RoutePlanException.InvalidInputException("Maximum 500 stops supported");

        ProblemDetail pd = handler.handleInvalidInput(ex);

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals("Invalid Input", pd.getTitle());
        assertTrue(pd.getDetail().contains("500 stops"));
        assertNotNull(pd.getType());
    }

    @Test
    void handleSolverError_shouldReturn500() {
        RoutePlanException.SolverException ex =
                new RoutePlanException.SolverException("TSP failed", new RuntimeException("cause"));

        ProblemDetail pd = handler.handleSolverError(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), pd.getStatus());
        assertEquals("Route Optimization Failed", pd.getTitle());
        assertTrue(pd.getDetail().contains("Solver error"));
        assertNotNull(pd.getType());
    }

    @Test
    void handleValidation_shouldReturn400WithFieldErrors() {
        // Simulate MethodArgumentNotValidException
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "warehouse.lat",
                "Warehouse latitude is required"));
        bindingResult.addError(new FieldError("request", "stops",
                "At least one delivery stop is required"));

        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(null, bindingResult);

        ProblemDetail pd = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals("Validation Failed", pd.getTitle());
        assertTrue(pd.getDetail().contains("warehouse.lat"));
        assertTrue(pd.getDetail().contains("stops"));
        assertNotNull(pd.getType());
    }

    @Test
    void handleGeneral_shouldReturn500() {
        Exception ex = new RuntimeException("Something broke");

        ProblemDetail pd = handler.handleGeneral(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), pd.getStatus());
        assertEquals("Internal Server Error", pd.getTitle());
        assertEquals("An unexpected error occurred", pd.getDetail());
        assertNotNull(pd.getType());
    }
}
