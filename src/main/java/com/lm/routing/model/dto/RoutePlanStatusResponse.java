package com.lm.routing.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RoutePlanStatusResponse {
    private String planId;
    private String status;
    private String phase;
    private Integer progress;
}
