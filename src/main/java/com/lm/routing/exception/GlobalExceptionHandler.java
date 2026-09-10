package com.lm.routing.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String msg(String code, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(code, args, code, locale);
    }

    @ExceptionHandler(RoutePlanException.NotFoundException.class)
    public ProblemDetail handleNotFound(RoutePlanException.NotFoundException ex) {
        log.warn("Not found: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle(msg("error.not_found", ex.getPlanId()));
        pd.setType(URI.create("https://lm-routing.dev/errors/not-found"));
        return pd;
    }

    @ExceptionHandler(RoutePlanException.InvalidInputException.class)
    public ProblemDetail handleInvalidInput(RoutePlanException.InvalidInputException ex) {
        log.warn("Invalid input: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle(msg("error.invalid_input", ex.getMessage()));
        pd.setType(URI.create("https://lm-routing.dev/errors/invalid-input"));
        return pd;
    }

    @ExceptionHandler(RoutePlanException.SolverException.class)
    public ProblemDetail handleSolverError(RoutePlanException.SolverException ex) {
        log.error("Solver error: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setTitle(msg("error.solver_failed", ex.getReason()));
        pd.setType(URI.create("https://lm-routing.dev/errors/solver-error"));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", errors);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errors);
        pd.setTitle("Validation Failed");
        pd.setType(URI.create("https://lm-routing.dev/errors/validation"));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setTitle(msg("error.internal"));
        pd.setType(URI.create("https://lm-routing.dev/errors/internal"));
        return pd;
    }
}
