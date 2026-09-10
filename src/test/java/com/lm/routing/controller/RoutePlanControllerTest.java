package com.lm.routing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.routing.exception.GlobalExceptionHandler;
import com.lm.routing.exception.RoutePlanException;
import com.lm.routing.model.dto.RoutePlanRequest;
import com.lm.routing.model.dto.RoutePlanResponse;
import com.lm.routing.model.dto.RoutePlanStatusResponse;
import com.lm.routing.service.RoutePlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Each request pins its locale explicitly. Combined with
 * spring.messages.fallback-to-system-locale=false this makes i18n title
 * assertions deterministic on any host, independent of the JVM default locale.
 */
@WebMvcTest({RoutePlanController.class, GlobalExceptionHandler.class})
class RoutePlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RoutePlanService routePlanService;

    // ===== POST /api/v1/route-plans =====

    @Test
    void createPlan_validRequest_shouldReturn202() throws Exception {
        RoutePlanResponse response = RoutePlanResponse.builder()
                .planId("plan-123")
                .status("PENDING")
                .createdAt(Instant.now())
                .build();

        when(routePlanService.createPlan(any())).thenReturn(response);

        String body = objectMapper.writeValueAsString(validRequest());

        mockMvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.planId").value("plan-123"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(header().exists("Location"));

        verify(routePlanService).executePlan("plan-123");
    }

    @Test
    void createPlan_missingWarehouse_shouldReturn400() throws Exception {
        String body = """
                {
                    "stops": [
                        {"id": "S1", "lat": 31.23, "lng": 121.47}
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createPlan_emptyStops_shouldReturn400() throws Exception {
        String body = """
                {
                    "warehouse": {"id": "WH1", "lat": 31.23, "lng": 121.47},
                    "stops": []
                }
                """;

        mockMvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createPlan_tooManyStops_shouldReturn400() throws Exception {
        RoutePlanException.InvalidInputException ex =
                new RoutePlanException.InvalidInputException("Maximum 500 stops supported, got 501");

        when(routePlanService.createPlan(any())).thenThrow(ex);

        String body = objectMapper.writeValueAsString(validRequest());

        mockMvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("输入无效：Maximum 500 stops supported, got 501"));
    }

    @Test
    void createPlan_tooManyStops_enLocale_shouldReturnEnglishTitle() throws Exception {
        RoutePlanException.InvalidInputException ex =
                new RoutePlanException.InvalidInputException("Maximum 500 stops supported, got 501");

        when(routePlanService.createPlan(any())).thenThrow(ex);

        String body = objectMapper.writeValueAsString(validRequest());

        mockMvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .locale(Locale.US))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid input: Maximum 500 stops supported, got 501"));
    }

    // ===== GET /api/v1/route-plans/{planId} =====

    @Test
    void getPlan_existing_shouldReturn200() throws Exception {
        RoutePlanResponse response = RoutePlanResponse.builder()
                .planId("plan-123")
                .status("COMPLETED")
                .progress(100)
                .createdAt(Instant.now())
                .completedAt(Instant.now())
                .build();

        when(routePlanService.getPlan("plan-123")).thenReturn(response);

        mockMvc.perform(get("/api/v1/route-plans/plan-123")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("plan-123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progress").value(100));
    }

    @Test
    void getPlan_notFound_shouldReturn404() throws Exception {
        when(routePlanService.getPlan("nonexistent"))
                .thenThrow(new RoutePlanException.NotFoundException("nonexistent"));

        mockMvc.perform(get("/api/v1/route-plans/nonexistent")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("未找到路线规划：nonexistent"));
    }

    @Test
    void getPlan_notFound_enLocale_shouldReturnEnglishTitle() throws Exception {
        when(routePlanService.getPlan("nonexistent"))
                .thenThrow(new RoutePlanException.NotFoundException("nonexistent"));

        mockMvc.perform(get("/api/v1/route-plans/nonexistent")
                        .locale(Locale.US))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Route plan not found: nonexistent"));
    }

    // ===== GET /api/v1/route-plans/{planId}/status =====

    @Test
    void getPlanStatus_shouldReturn200() throws Exception {
        RoutePlanStatusResponse status = RoutePlanStatusResponse.builder()
                .planId("plan-123")
                .status("SOLVING_TSP")
                .phase("Optimizing route order with OR-Tools")
                .progress(15)
                .build();

        when(routePlanService.getPlanStatus("plan-123")).thenReturn(status);

        mockMvc.perform(get("/api/v1/route-plans/plan-123/status")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("plan-123"))
                .andExpect(jsonPath("$.status").value("SOLVING_TSP"))
                .andExpect(jsonPath("$.phase").value("Optimizing route order with OR-Tools"))
                .andExpect(jsonPath("$.progress").value(15));
    }

    @Test
    void getPlanStatus_notFound_shouldReturn404() throws Exception {
        when(routePlanService.getPlanStatus("nonexistent"))
                .thenThrow(new RoutePlanException.NotFoundException("nonexistent"));

        mockMvc.perform(get("/api/v1/route-plans/nonexistent/status")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isNotFound());
    }

    // ===== Helpers =====

    private RoutePlanRequest validRequest() {
        RoutePlanRequest req = new RoutePlanRequest();

        RoutePlanRequest.WarehouseInfo warehouse = new RoutePlanRequest.WarehouseInfo();
        warehouse.setId("WH-001");
        warehouse.setName("Shanghai Warehouse");
        warehouse.setLat(31.2304);
        warehouse.setLng(121.4737);
        req.setWarehouse(warehouse);

        RoutePlanRequest.StopInfo stop1 = new RoutePlanRequest.StopInfo();
        stop1.setId("S-001");
        stop1.setLat(31.2450);
        stop1.setLng(121.5050);
        stop1.setName("Stop 1");

        RoutePlanRequest.StopInfo stop2 = new RoutePlanRequest.StopInfo();
        stop2.setId("S-002");
        stop2.setLat(31.2200);
        stop2.setLng(121.4900);
        stop2.setName("Stop 2");

        req.setStops(java.util.List.of(stop1, stop2));
        return req;
    }
}
