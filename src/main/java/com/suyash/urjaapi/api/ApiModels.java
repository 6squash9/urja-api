package com.suyash.urjaapi.api;

import java.util.List;
import java.util.Map;

/**
 * The clean shapes OUR API returns ("the menu card").
 * Different from PortalModels on purpose: consistent names, real numbers instead of strings,
 * ISO-8601 timestamps instead of DD/MM/YYYY.
 */
public final class ApiModels {

    private ApiModels() {
    }

    /** Standard wrapper for paginated lists. page is 1-based. */
    public record Page<T>(List<T> data, int page, int size, long total) {
    }

    public record NetworkNode(String code, String name) {
    }

    /** Where the meter sits in the electricity network, from the top (zone) down to its transformer. */
    public record Hierarchy(NetworkNode zone, NetworkNode circle, NetworkNode division, NetworkNode subdivision,
                            NetworkNode substation, NetworkNode feeder, NetworkNode transformer) {
    }

    public record Location(Double lat, Double lng) {
    }

    public record Meter(String meterId, String serialNo, String make, String phaseType,
                        String status, String installType, String build, String transformerCode,
                        Hierarchy hierarchy, Location location) {
    }

    public record Transformer(String code, String name, String feederCode, Integer capacityKva, int meterCount) {
    }

    /**
     * One half-hourly reading.
     * kwh / kvah are cumulative register values (they only go up, like an odometer).
     * kwhSincePrevious = actual energy used since the previous reading (null for the first one).
     * voltage = per phase, e.g. {"R": 227.0}.
     */
    public record Reading(String timestamp, Double kwh, Double kvah, Map<String, Double> voltage,
                          Double kwhSincePrevious) {
    }

    public record Readings(String meterId, String timezone, int count, String from, String to,
                           List<Reading> readings) {
    }

    public record Status(boolean ready, String loadedAt, int meterCount, int transformerCount,
                         String lastRefreshError) {
    }

    public record ApiError(String error, String message) {
    }
}
