package com.suyash.urjaapi.portal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Raw shapes exactly as the portal returns them.
 * Kept separate from our own clean API models, so a change in the portal only touches this file + the mapper.
 * ignoreUnknown = true: if the portal adds a field, we don't crash.
 */
public final class PortalModels {

    private PortalModels() {
    }

    /** GET /portal/keys */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeysResponse(KeysData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeysData(String signingSecret) {
    }

    /** GET /portal/export?page=N (signed) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportResponse(List<Meter> data, Integer total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meter(String meterId, String serialNo, String make, String phaseType,
                        String installStatus, String installType, String build, String dtCode,
                        Hierarchy hierarchy, Geo geo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hierarchy(Node zone, Node circle, Node division, Node subdivision,
                            Node substation, Node feeder, Node dt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String name, String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Geo(Double lat, Double lng) {
    }

    /** GET /portal/dts?page=N */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DtPage(List<Dt> data, Integer total, Integer page, Integer pageSize) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dt(String code, String name, String feederCode, Integer capacityKva) {
    }

    /**
     * GET /portal/meters/{id}/energy
     * Every value comes back as a string ("6850.32") and the field names may differ per meter
     * (e.g. voltR, maybe voltY/voltB on three-phase meters), so we read each row as a plain map.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnergyResponse(List<Map<String, Object>> data) {
    }
}
