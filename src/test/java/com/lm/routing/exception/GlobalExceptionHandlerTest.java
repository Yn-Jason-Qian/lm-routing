package com.lm.routing.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.support.StaticMessageSource;
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
        StaticMessageSource ms = new StaticMessageSource();
        java.util.Locale defaultLocale = java.util.Locale.getDefault();
        ms.addMessage("error.not_found", defaultLocale, "error.not_found");
        ms.addMessage("error.invalid_input", defaultLocale, "error.invalid_input");
        ms.addMessage("error.solver_failed", defaultLocale, "error.solver_failed");
        ms.addMessage("error.internal", defaultLocale, "Internal Server Error");
        handler = new GlobalExceptionHandler(ms);
    }

    @Test
    void handleNotFound_shouldReturn404() {
        RoutePlanException.NotFoundException ex =
                new RoutePlanException.NotFoundException("plan-123");

        ProblemDetail pd = handler.handleNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND.value(), pd.getStatus());
        assertEquals("error.not_found", pd.getTitle());
        assertTrue(pd.getDetail().contains("plan-123"));
        assertNotNull(pd.getType());
    }

    @Test
    void handleInvalidInput_shouldReturn400() {
        RoutePlanException.InvalidInputException ex =
                new RoutePlanException.InvalidInputException("Maximum 500 stops supported");

        ProblemDetail pd = handler.handleInvalidInput(ex);

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals("error.invalid_input", pd.getTitle());
        assertTrue(pd.getDetail().contains("500 stops"));
        assertNotNull(pd.getType());
    }

    @Test
    void handleSolverError_shouldReturn500() {
        RoutePlanException.SolverException ex =
                new RoutePlanException.SolverException("TSP failed", new RuntimeException("cause"));

        ProblemDetail pd = handler.handleSolverError(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), pd.getStatus());
        assertEquals("error.solver_failed", pd.getTitle());
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

    /**
     * With the real message bundles, the solver title must use the template's
     * prefix and the raw failure reason — not wrap the full exception message.
     */
    @Test
    void handleSolverError_withLocalizedMessages_shouldNotDuplicatePrefix() {
        GlobalExceptionHandler localized = new GlobalExceptionHandler(realMessageSource());
        RoutePlanException.SolverException ex =
                new RoutePlanException.SolverException("TSP failed", new RuntimeException("cause"));

        LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        try {
            ProblemDetail pd = localized.handleSolverError(ex);
            assertEquals("求解器错误：TSP failed", pd.getTitle());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /**
     * The internal-error template has no placeholder, so the title must not
     * end with a dangling separator.
     */
    @Test
    void handleGeneral_withLocalizedMessages_shouldNotHaveEmptySuffix() {
        GlobalExceptionHandler localized = new GlobalExceptionHandler(realMessageSource());

        LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        try {
            ProblemDetail pd = localized.handleGeneral(new RuntimeException("boom"));
            assertEquals("服务器内部错误", pd.getTitle());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    private MessageSource realMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        return ms;
    }
}
