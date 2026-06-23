package com.lm.routing.infrastructure.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistanceCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private DistanceCacheService cacheService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new DistanceCacheService(redisTemplate, 7);
    }

    @Test
    void putAndGet_shouldStoreAndRetrieveDistance() {
        // Simulate Redis storing an object
        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("distanceMeters", 1500L);
            map.put("durationSeconds", 180L);
            return map;
        });

        // Put
        cacheService.put(31.23, 121.47, 31.24, 121.48, 1500, 180);
        verify(valueOperations).set(anyString(), any(), eq(Duration.ofDays(7)));

        // Get
        Optional<DistanceCacheService.CachedDistance> result =
                cacheService.get(31.23, 121.47, 31.24, 121.48);

        assertTrue(result.isPresent());
        assertEquals(1500L, result.get().distanceMeters());
        assertEquals(180L, result.get().durationSeconds());
    }

    @Test
    void get_cacheMiss_shouldReturnEmpty() {
        when(valueOperations.get(anyString())).thenReturn(null);

        Optional<DistanceCacheService.CachedDistance> result =
                cacheService.get(31.23, 121.47, 31.24, 121.48);

        assertTrue(result.isEmpty());
    }

    @Test
    void putAndGet_orderInsensitive_shouldUseSameKey() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        // Put with A→B
        cacheService.put(31.23, 121.47, 31.25, 121.50, 1500, 180);

        verify(valueOperations).set(keyCaptor.capture(), any(), any());
        String keyAB = keyCaptor.getValue();

        // Put with B→A (same coordinates, reversed order)
        cacheService.put(31.25, 121.50, 31.23, 121.47, 1500, 180);

        verify(valueOperations, times(2)).set(keyCaptor.capture(), any(), any());
        String keyBA = keyCaptor.getAllValues().get(1);

        // Keys should be the same (order-insensitive)
        assertEquals(keyAB, keyBA);
    }

    @Test
    void get_redisFailure_shouldReturnEmpty() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        Optional<DistanceCacheService.CachedDistance> result =
                cacheService.get(31.23, 121.47, 31.24, 121.48);

        assertTrue(result.isEmpty());
    }

    @Test
    void put_redisFailure_shouldNotThrow() {
        doThrow(new RuntimeException("Redis down")).when(valueOperations)
                .set(anyString(), any(), any());

        // Should not throw
        assertDoesNotThrow(() ->
                cacheService.put(31.23, 121.47, 31.24, 121.48, 1500, 180));
    }

    @Test
    void get_nonMapValue_shouldReturnEmpty() {
        // If Redis returns something that's not a Map
        when(valueOperations.get(anyString())).thenReturn("unexpected string value");

        Optional<DistanceCacheService.CachedDistance> result =
                cacheService.get(31.23, 121.47, 31.24, 121.48);

        assertTrue(result.isEmpty());
    }
}
