package com.suyash.urjaapi.api;

import com.suyash.urjaapi.portal.PortalModels;
import com.suyash.urjaapi.store.MeterStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** The "front desk" for meters. Each endpoint answers one question a user would ask. */
@RestController
@RequestMapping("/api/meters")
public class MeterController {

    private static final int MAX_PAGE_SIZE = 500;

    private final MeterStore store;

    public MeterController(MeterStore store) {
        this.store = store;
    }

    /**
     * "Show me all meters" + optional filters the portal itself can't do.
     * All filters are optional, case-insensitive, and combined with AND.
     * Example: /api/meters?status=Faulty&make=Genus&page=1&size=20
     */
    @GetMapping
    public ApiModels.Page<ApiModels.Meter> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String make,
            @RequestParam(required = false) String phaseType,
            @RequestParam(required = false) String transformerCode,
            @RequestParam(required = false) String feederCode,
            @RequestParam(required = false, name = "q") String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {

        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

        Predicate<PortalModels.Meter> filter = m -> true;
        filter = filter.and(m -> matches(status, m.installStatus()));
        filter = filter.and(m -> matches(make, m.make()));
        filter = filter.and(m -> matches(phaseType, m.phaseType()));
        filter = filter.and(m -> matches(transformerCode, m.dtCode()));
        filter = filter.and(m -> matches(feederCode,
                m.hierarchy() == null || m.hierarchy().feeder() == null ? null : m.hierarchy().feeder().code()));
        filter = filter.and(m -> search == null || search.isBlank()
                || contains(m.meterId(), search) || contains(m.serialNo(), search));

        List<PortalModels.Meter> matching = store.current().meters().stream().filter(filter).toList();
        List<ApiModels.Meter> pageData = matching.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(ApiMapper::toMeter)
                .toList();
        return new ApiModels.Page<>(pageData, safePage, safeSize, matching.size());
    }

    /** "Show me meter J100002" */
    @GetMapping("/{meterId}")
    public ApiModels.Meter get(@PathVariable String meterId) {
        return ApiMapper.toMeter(findMeter(meterId));
    }

    /** "How much electricity did this meter use?" Fetched live from the portal, then cleaned. */
    @GetMapping("/{meterId}/readings")
    public ApiModels.Readings readings(@PathVariable String meterId) {
        PortalModels.Meter meter = findMeter(meterId); // 404 early for unknown meters, before calling the portal
        return ApiMapper.toReadings(meter.meterId(), store.liveEnergy(meter.meterId()));
    }

    private PortalModels.Meter findMeter(String meterId) {
        PortalModels.Meter meter = store.current().metersById().get(meterId.trim().toUpperCase(Locale.ROOT));
        if (meter == null) {
            throw new NotFoundException("No meter with id " + meterId);
        }
        return meter;
    }

    /** No filter given → match everything. Otherwise exact match, ignoring case. */
    private static boolean matches(String filter, String value) {
        return filter == null || filter.isBlank() || (value != null && value.equalsIgnoreCase(filter.trim()));
    }

    private static boolean contains(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search.trim().toLowerCase(Locale.ROOT));
    }
}
