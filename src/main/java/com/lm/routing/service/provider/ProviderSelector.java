package com.lm.routing.service.provider;

import com.lm.routing.service.CountryDetector;
import com.lm.routing.service.CountryRegion;
import com.lm.routing.service.GeoPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Routes to the best {@link MatrixProvider} based on configuration, availability,
 * and geographic region (country-aware selection).
 *
 * All {@link MatrixProvider} beans are auto-discovered by Spring and indexed by name
 * for fast lookup.
 *
 * AUTO mode selection (country-aware):
 *  1. Detect country from warehouse coordinates
 *  2. Try each provider in the country's preference list (first available wins)
 *  3. If none available, fall back to global availability chain
 *  4. Ultimate fallback: Haversine (always available)
 */
@Slf4j
@Service
public class ProviderSelector {

    private final Map<String, MatrixProvider> providerByName;
    private final List<MatrixProvider> allProviders;
    private final CountryDetector countryDetector;

    @Value("${routing.strategy:AUTO}")
    private String configuredStrategy;

    public ProviderSelector(List<MatrixProvider> providers, CountryDetector countryDetector) {
        this.providerByName = providers.stream()
                .collect(Collectors.toMap(
                        MatrixProvider::getName,
                        p -> p,
                        (a, b) -> a // keep first on duplicate name
                ));
        this.allProviders = providers.stream()
                .sorted(Comparator.comparingInt(MatrixProvider::getPriority))
                .collect(Collectors.toList());
        this.countryDetector = countryDetector;

        log.info("ProviderSelector initialized: registered providers = {}",
                providerByName.keySet());
    }

    /**
     * Select the best provider for the given planning request.
     *
     * @param allPoints all geographic points (warehouse at index 0)
     * @param warehouse warehouse coordinate
     * @return the selected provider (never null — falls back to Haversine)
     * @throws IllegalArgumentException if a forced strategy is not found
     */
    public MatrixProvider selectProvider(List<GeoPoint> allPoints, GeoPoint warehouse) {
        // 1. Force a specific strategy?
        if (!"AUTO".equalsIgnoreCase(configuredStrategy)) {
            MatrixProvider forced = providerByName.get(configuredStrategy.toUpperCase());
            if (forced != null) {
                if (forced.isAvailable()) {
                    log.info("Forced strategy '{}' selected", configuredStrategy.toUpperCase());
                    return forced;
                }
                log.warn("Forced strategy '{}' is unavailable, falling back to AUTO",
                        configuredStrategy.toUpperCase());
            } else {
                log.warn("Unknown strategy '{}', falling back to AUTO",
                        configuredStrategy.toUpperCase());
            }
        }

        // 2. AUTO mode: country-aware selection
        return selectAuto(allPoints, warehouse);
    }

    /**
     * Country-aware auto selection.
     *
     * Uses country-specific provider preferences first, then falls back
     * to a global availability chain. For Google providers, prefers
     * CLUSTER_HYBRID for large problems (>50 points) and GOOGLE_FULL
     * for small problems (≤50 points).
     */
    private MatrixProvider selectAuto(List<GeoPoint> allPoints, GeoPoint warehouse) {
        int n = allPoints.size();

        // Detect country from warehouse coordinates
        CountryRegion region = countryDetector.detectCountry(warehouse.getLat(), warehouse.getLng());

        // Try country-preferred providers in order
        if (region != CountryRegion.GLOBAL) {
            log.info("AUTO: detected region = {} (warehouse: {}, {})",
                    region.name(), warehouse.getLat(), warehouse.getLng());
        }

        for (String preferredName : region.getPreferredProviders()) {
            MatrixProvider provider = providerByName.get(preferredName);
            if (provider == null) continue; // Provider not registered

            // Handle Google preference: choose Full vs Cluster based on problem size
            if ("GOOGLE_FULL".equals(preferredName) && n > 50) {
                // Large problem: prefer cluster-hybrid over full
                MatrixProvider cluster = providerByName.get("CLUSTER_HYBRID");
                if (cluster != null && cluster.isAvailable()) {
                    log.info("AUTO: {} ({}) → CLUSTER_HYBRID ({} points > 50)",
                            region.name(), preferredName, n);
                    return cluster;
                }
            }

            if (provider.isAvailable()) {
                log.info("AUTO: {} → {} (preferred #{} for region)",
                        region.name(), provider.getName(),
                        region.getPreferredProviders().indexOf(preferredName) + 1);
                return provider;
            }
        }

        // Country preferences exhausted — fall back to global availability chain
        log.info("AUTO: no country-preferred provider available for {}, falling back to global chain",
                region.name());
        return selectGlobalFallback(allPoints);
    }

    /**
     * Global availability chain — ignores country, picks first available.
     * Priority: OSRM → AMap → Google → Mapbox → Bing → AWS → Haversine.
     */
    private MatrixProvider selectGlobalFallback(List<GeoPoint> allPoints) {
        int n = allPoints.size();

        // 1. OSRM
        MatrixProvider osrm = providerByName.get("OSRM");
        if (osrm != null && osrm.isAvailable()) {
            log.info("AUTO → OSRM (free real road matrix, {} points)", n);
            return osrm;
        }

        // 2. AMap
        MatrixProvider amap = providerByName.get("AMAP_WAYPOINTS");
        if (amap != null && amap.isAvailable()) {
            log.info("AUTO → AMAP_WAYPOINTS (Haversine + waypoint enrichment, {} points)", n);
            return amap;
        }

        // 3. Google (Full for small, Cluster for large)
        if (n <= 50) {
            MatrixProvider googleFull = providerByName.get("GOOGLE_FULL");
            if (googleFull != null && googleFull.isAvailable()) {
                log.info("AUTO → GOOGLE_FULL ({} points ≤ 50)", n);
                return googleFull;
            }
        } else {
            MatrixProvider clusterHybrid = providerByName.get("CLUSTER_HYBRID");
            if (clusterHybrid != null && clusterHybrid.isAvailable()) {
                log.info("AUTO → CLUSTER_HYBRID ({} points > 50)", n);
                return clusterHybrid;
            }
        }

        // 4. Mapbox
        MatrixProvider mapbox = providerByName.get("MAPBOX");
        if (mapbox != null && mapbox.isAvailable()) {
            log.info("AUTO → MAPBOX (fallback, {} points)", n);
            return mapbox;
        }

        // 5. Bing
        MatrixProvider bing = providerByName.get("BING");
        if (bing != null && bing.isAvailable()) {
            log.info("AUTO → BING (fallback, {} points)", n);
            return bing;
        }

        // 6. AWS
        MatrixProvider aws = providerByName.get("AWS_ROUTES");
        if (aws != null && aws.isAvailable()) {
            log.info("AUTO → AWS_ROUTES (Haversine + AWS route enrichment, {} points)", n);
            return aws;
        }

        // 7. Ultimate fallback: Haversine (always available)
        MatrixProvider haversine = providerByName.get("HAVERSINE_ONLY");
        if (haversine != null) {
            log.info("AUTO → HAVERSINE_ONLY (no external API available, {} points)", n);
            return haversine;
        }

        throw new IllegalStateException("No MatrixProvider available — this is a bug");
    }

    /**
     * Find a provider by name. Returns null if not found.
     */
    public MatrixProvider findByName(String name) {
        return providerByName.get(name.toUpperCase());
    }

    /**
     * Get all registered provider names for status/debug endpoints.
     */
    public List<String> getRegisteredProviders() {
        return allProviders.stream()
                .map(p -> p.getName() + (p.isAvailable() ? " ✓" : " ✗"))
                .collect(Collectors.toList());
    }
}
