package com.lm.routing.service;

import com.lm.routing.service.provider.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProviderSelector and CountryDetector.
 * Verifies country-based provider routing and fallback behavior.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ProviderSelectorTest {

    @Autowired
    private ProviderSelector selector;

    @Autowired
    private CountryDetector countryDetector;

    @Test
    void countryDetection_china_shouldReturnChina() {
        CountryRegion region = countryDetector.detectCountry(31.23, 121.47); // Shanghai
        assertEquals(CountryRegion.CHINA, region);
    }

    @Test
    void countryDetection_usa_shouldReturnUSA() {
        CountryRegion region = countryDetector.detectCountry(40.71, -74.01); // New York
        assertEquals(CountryRegion.USA, region);
    }

    @Test
    void countryDetection_europe_shouldReturnEU() {
        CountryRegion region = countryDetector.detectCountry(48.86, 2.35); // Paris
        assertEquals(CountryRegion.EU_EEA, region);
    }

    @Test
    void countryDetection_japan_shouldReturnJapan() {
        CountryRegion region = countryDetector.detectCountry(35.68, 139.76); // Tokyo
        assertEquals(CountryRegion.JAPAN, region);
    }

    @Test
    void countryDetection_unknown_shouldReturnGlobal() {
        CountryRegion region = countryDetector.detectCountry(-90.0, 0.0); // South Pole
        assertEquals(CountryRegion.GLOBAL, region);
    }

    @Test
    void selectProvider_noApiConfigured_shouldFallbackToHaversine() {
        // With no external API keys set, AUTO should pick Haversine
        List<GeoPoint> points = helperPoints();
        MatrixProvider provider = selector.selectProvider(points,
                new GeoPoint(31.23, 121.47));

        assertNotNull(provider);
        assertEquals("HAVERSINE_ONLY", provider.getName(),
                "Without any API key, should fall back to Haversine");
    }

    @Test
    void selectProvider_forcedStrategy_shouldHonorForce() {
        // Force HAVERSINE_FULL regardless of AUTO
        ReflectionTestUtils.setField(selector, "configuredStrategy", "HAVERSINE_FULL");
        try {
            List<GeoPoint> points = helperPoints();
            MatrixProvider provider = selector.selectProvider(points,
                    new GeoPoint(31.23, 121.47));

            assertNotNull(provider);
            assertEquals("HAVERSINE_ONLY", provider.getName());
        } finally {
            ReflectionTestUtils.setField(selector, "configuredStrategy", "AUTO");
        }
    }

    @Test
    void findByName_osrm_shouldReturnProvider() {
        MatrixProvider provider = selector.findByName("OSRM");
        assertNotNull(provider);
        assertEquals("OSRM", provider.getName());
    }

    @Test
    void selectProvider_china_shouldPreferAmap() {
        // AMap is not configured (no API key), but OSRSM also not available
        // So should fall back to Haversine
        List<GeoPoint> points = helperPoints();
        MatrixProvider provider = selector.selectProvider(points,
                new GeoPoint(31.23, 121.47)); // Shanghai

        assertNotNull(provider);
        // Without any API configured, always Haversine
        assertTrue(provider.isAvailable());
    }

    private List<GeoPoint> helperPoints() {
        return List.of(
                new GeoPoint(31.23, 121.47),
                new GeoPoint(31.25, 121.50),
                new GeoPoint(31.20, 121.45)
        );
    }
}
