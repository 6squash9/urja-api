package com.suyash.urjaapi.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiMapperTest {

    /** Rows shaped exactly like the portal's /energy response (all values are strings), deliberately out of order. */
    private final List<Map<String, Object>> raw = List.of(
            Map.of("timestamp", "24/06/2026 00:00", "kwh", "6850.78", "kvah", "7398.84", "voltR", "236"),
            Map.of("timestamp", "23/06/2026 23:30", "kwh", "6850.32", "kvah", "7398.35", "voltR", "227"),
            Map.of("timestamp", "24/06/2026 00:30", "kwh", "", "kvah", "7399.33", "voltR", "238"),
            Map.of("timestamp", "not a date", "kwh", "1", "kvah", "1", "voltR", "1"));

    @Test
    void cleansPortalReadings() {
        ApiModels.Readings result = ApiMapper.toReadings("J100002", raw);

        // the unreadable timestamp row is skipped, the rest are sorted oldest → newest
        assertEquals(3, result.count());
        assertEquals("2026-06-23T23:30+05:30", result.from());
        assertEquals("2026-06-24T00:30+05:30", result.to());

        ApiModels.Reading first = result.readings().get(0);
        assertEquals(6850.32, first.kwh());              // text → number
        assertEquals(227.0, first.voltage().get("R"));   // voltR → voltage.R
        assertNull(first.kwhSincePrevious());            // nothing before the first reading

        ApiModels.Reading second = result.readings().get(1);
        assertEquals(0.46, second.kwhSincePrevious());   // 6850.78 - 6850.32: odometer → actual usage

        ApiModels.Reading third = result.readings().get(2);
        assertNull(third.kwh());                         // blank value → null instead of crashing
        assertNull(third.kwhSincePrevious());
    }
}
