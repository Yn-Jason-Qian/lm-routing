package com.lm.routing.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Simple API Key authentication filter.
 *
 * When api.key is set, all /api/** requests must include
 * X-API-Key header matching the configured value.
 * Actuator and Swagger endpoints are excluded.
 */
@Component
public class ApiKeyFilter implements Filter {

    @Value("${routing.api.key:}")
    private String apiKey;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // Only protect /api/** endpoints when a key is configured
        if (apiKey != null && !apiKey.isBlank() && path.startsWith("/api/")) {
            String providedKey = request.getHeader("X-API-Key");
            if (!apiKey.equals(providedKey)) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"title\":\"Unauthorized\",\"detail\":\"Missing or invalid X-API-Key header\"}");
                return;
            }
        }

        chain.doFilter(req, res);
    }
}
