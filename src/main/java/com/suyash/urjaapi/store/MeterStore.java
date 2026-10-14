package com.suyash.urjaapi.store;

import com.suyash.urjaapi.portal.PortalClient;
import com.suyash.urjaapi.portal.PortalModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The "brain": keeps an in-memory copy of all meters and transformers.
 *
 * Decision (see README): meters + transformers come from a cache that is downloaded once at startup and
 * refreshed every few minutes, because the portal can't filter and 403 meters fit easily in memory.
 * Consumption is NOT cached: it's fetched live per meter.
 */
@Component
public class MeterStore {

    private static final Logger log = LoggerFactory.getLogger(MeterStore.class);

    /** Everything loaded in one refresh. Replaced as a whole, so readers never see half-updated data. */
    public record Snapshot(List<PortalModels.Meter> meters,
                           Map<String, PortalModels.Meter> metersById,
                           List<PortalModels.Dt> transformers,
                           Map<String, PortalModels.Dt> transformersByCode,
                           Instant loadedAt) {
    }

    private final PortalClient portal;
    private volatile Snapshot snapshot;
    private volatile String lastError = "Not loaded yet";

    public MeterStore(PortalClient portal) {
        this.portal = portal;
    }

    /** Runs once right after startup, then every cache.refresh-interval-ms. If a refresh fails, the old data is kept. */
    @Scheduled(initialDelay = 0, fixedDelayString = "${cache.refresh-interval-ms:600000}")
    public void refresh() {
        if (!portal.hasCredentials()) {
            lastError = "PORTAL_EMAIL / PORTAL_PASSWORD are not set";
            log.warn("Skipping cache refresh: {}", lastError);
            return;
        }
        try {
            List<PortalModels.Meter> meters = portal.fetchAllMeters();
            List<PortalModels.Dt> transformers = portal.fetchAllTransformers();

            // putIfAbsent: if the portal ever sends a duplicate ID, keep the first one instead of crashing
            Map<String, PortalModels.Meter> metersById = new LinkedHashMap<>();
            meters.forEach(m -> metersById.putIfAbsent(m.meterId(), m));
            Map<String, PortalModels.Dt> transformersByCode = new LinkedHashMap<>();
            transformers.forEach(t -> transformersByCode.putIfAbsent(t.code(), t));

            snapshot = new Snapshot(List.copyOf(metersById.values()), metersById,
                    List.copyOf(transformersByCode.values()), transformersByCode, Instant.now());
            lastError = null;
            log.info("Cache refreshed: {} meters, {} transformers", metersById.size(), transformersByCode.size());
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("Cache refresh failed, keeping the previous data", e);
        }
    }

    /** Current data, or a CacheNotReadyException (→ HTTP 503) if nothing has loaded yet. */
    public Snapshot current() {
        Snapshot s = snapshot;
        if (s == null) {
            throw new CacheNotReadyException("Data from the portal is not loaded yet: " + lastError);
        }
        return s;
    }

    public Optional<Snapshot> currentIfLoaded() {
        return Optional.ofNullable(snapshot);
    }

    public String lastError() {
        return lastError;
    }

    /** Consumption is always live (see class comment). */
    public List<Map<String, Object>> liveEnergy(String meterId) {
        return portal.fetchEnergy(meterId);
    }
}
