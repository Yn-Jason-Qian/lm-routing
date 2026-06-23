package com.lm.routing.controller;

import com.lm.routing.model.dto.RoutePlanRequest;
import com.lm.routing.model.dto.RoutePlanResponse;
import com.lm.routing.model.dto.RoutePlanStatusResponse;
import com.lm.routing.service.RoutePlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/route-plans")
public class RoutePlanController {

    private final RoutePlanService routePlanService;

    public RoutePlanController(RoutePlanService routePlanService) {
        this.routePlanService = routePlanService;
    }

    /**
     * Create a new route planning task.
     * Returns 202 Accepted with planId — the solver runs asynchronously.
     */
    @PostMapping
    public ResponseEntity<RoutePlanResponse> createPlan(@Valid @RequestBody RoutePlanRequest request) {
        RoutePlanResponse response = routePlanService.createPlan(request);

        // Trigger async execution
        routePlanService.executePlan(response.getPlanId());

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/route-plans/" + response.getPlanId()))
                .body(response);
    }

    /**
     * Get the full route plan (includes result if completed).
     */
    @GetMapping("/{planId}")
    public ResponseEntity<RoutePlanResponse> getPlan(@PathVariable String planId) {
        RoutePlanResponse response = routePlanService.getPlan(planId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get lightweight status + progress (for polling).
     */
    @GetMapping("/{planId}/status")
    public ResponseEntity<RoutePlanStatusResponse> getPlanStatus(@PathVariable String planId) {
        RoutePlanStatusResponse status = routePlanService.getPlanStatus(planId);
        return ResponseEntity.ok(status);
    }
}
