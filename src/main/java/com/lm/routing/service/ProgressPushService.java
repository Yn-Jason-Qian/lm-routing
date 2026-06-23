package com.lm.routing.service;

import com.lm.routing.model.dto.RoutePlanStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Pushes real-time route planning progress to WebSocket subscribers.
 *
 * Each plan has its own topic: /topic/plan/{planId}
 * Clients subscribe to receive status, progress, and message updates
 * as the solver moves through each phase.
 */
@Slf4j
@Service
public class ProgressPushService {

    private final SimpMessagingTemplate messagingTemplate;

    public ProgressPushService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Push a progress update to all subscribers of a given plan.
     *
     * @param planId   the route plan ID
     * @param status   current plan status (PENDING, BUILDING_MATRIX, ...)
     * @param message  human-readable phase description
     * @param progress completion percentage (0-100)
     */
    public void pushProgress(String planId, String status, String message, int progress) {
        RoutePlanStatusResponse payload = RoutePlanStatusResponse.builder()
                .planId(planId)
                .status(status)
                .phase(message)
                .progress(progress)
                .build();

        String destination = "/topic/plan/" + planId;
        messagingTemplate.convertAndSend(destination, payload);
        log.debug("Pushed progress: {} → {} ({}%)", planId, status, progress);
    }
}
