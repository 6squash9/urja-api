package com.suyash.urjaapi.api;

import com.suyash.urjaapi.portal.PortalModels;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns the portal's raw shapes into our clean API shapes. All the "cleaning" lives here. */
public final class ApiMapper {

    /** The portal sends "23/06/2026 23:30" with no time zone. */
    static final DateTimeFormatter PORTAL_TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** ASSUMPTION: portal timestamps are Indian Standard Time (the utility is in Jaipur). Documented in the README. */
    static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private ApiMapper() {
    }

    // ---------------------------------------------------------------- meters

    public static ApiModels.Meter toMeter(PortalModels.Meter m) {
        return new ApiModels.Meter(
                m.meterId(), m.serialNo(), m.make(), m.phaseType(),
                m.installStatus(), m.installType(), m.build(), m.dtCode(),
                toHierarchy(m.hierarchy()),
                m.geo() == null ? null : new ApiModels.Location(m.geo().lat(), m.geo().lng()));
    }

    private static ApiModels.Hierarchy toHierarchy(PortalModels.Hierarchy h) {
        if (h == null) {
            return null;
        }
        return new ApiModels.Hierarchy(node(h.zone()), node(h.circle()), node(h.division()),
                node(h.subdivision()), node(h.substation()), node(h.feeder()), node(h.dt()));
    }

    private static ApiModels.NetworkNode node(PortalModels.Node n) {
        return n == null ? null : new ApiModels.NetworkNode(n.code(), n.name());
    }

    // ---------------------------------------------------------------- transformers

    public static ApiModels.Transformer toTransformer(PortalModels.Dt dt, int meterCount) {
        return new ApiModels.Transformer(dt.code(), dt.name(), dt.feederCode(), dt.capacityKva(), meterCount);
    }

    // ---------------------------------------------------------------- readings

    /**
     * Cleans the raw /energy rows:
     *  - "23/06/2026 23:30" → "2026-06-23T23:30+05:30"
     *  - "6850.32" (text) → 6850.32 (number); blank or invalid → null instead of crashing
     *  - voltR / voltY / voltB → voltage: {"R": .., "Y": .., "B": ..}
     *  - adds kwhSincePrevious, because kwh is a running total
     * Rows with an unreadable timestamp are skipped. Rows are sorted oldest → newest.
     */
    public static ApiModels.Readings toReadings(String meterId, List<Map<String, Object>> rawRows) {
        record Parsed(LocalDateTime time, Map<String, Object> raw) {
        }

        List<Parsed> parsed = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            LocalDateTime time = parseTimestamp(row.get("timestamp"));
            if (time != null) {
                parsed.add(new Parsed(time, row));
            }
        }
        parsed.sort(Comparator.comparing(Parsed::time));

        List<ApiModels.Reading> readings = new ArrayList<>();
        Double previousKwh = null;
        for (Parsed p : parsed) {
            Double kwh = toDouble(p.raw().get("kwh"));
            Double kvah = toDouble(p.raw().get("kvah"));
            Double used = (kwh != null && previousKwh != null) ? round3(kwh - previousKwh) : null;
            readings.add(new ApiModels.Reading(iso(p.time()), kwh, kvah, voltages(p.raw()), used));
            if (kwh != null) {
                previousKwh = kwh;
            }
        }

        String from = parsed.isEmpty() ? null : iso(parsed.get(0).time());
        String to = parsed.isEmpty() ? null : iso(parsed.get(parsed.size() - 1).time());
        return new ApiModels.Readings(meterId, "Asia/Kolkata (+05:30, assumed)", readings.size(), from, to, readings);
    }

    private static Map<String, Double> voltages(Map<String, Object> row) {
        Map<String, Double> volts = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (key.startsWith("volt") && key.length() > 4) {
                volts.put(key.substring(4), toDouble(value)); // "voltR" → "R"
            }
        });
        return volts;
    }

    static LocalDateTime parseTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.toString().trim(), PORTAL_TIMESTAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String iso(LocalDateTime time) {
        return OffsetDateTime.of(time, IST).toString();
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
