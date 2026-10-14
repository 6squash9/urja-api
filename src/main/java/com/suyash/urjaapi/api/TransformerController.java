package com.suyash.urjaapi.api;

import com.suyash.urjaapi.portal.PortalModels;
import com.suyash.urjaapi.store.MeterStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** The "front desk" for distribution transformers (DTs). */
@RestController
@RequestMapping("/api/transformers")
public class TransformerController {

    private final MeterStore store;

    public TransformerController(MeterStore store) {
        this.store = store;
    }

    /** "Show me all transformers" (only ~40, so no pagination needed). Includes how many meters each one feeds. */
    @GetMapping
    public List<ApiModels.Transformer> list() {
        MeterStore.Snapshot snapshot = store.current();
        Map<String, Long> meterCounts = snapshot.meters().stream()
                .filter(m -> m.dtCode() != null)
                .collect(Collectors.groupingBy(PortalModels.Meter::dtCode, Collectors.counting()));
        return snapshot.transformers().stream()
                .map(dt -> ApiMapper.toTransformer(dt, meterCounts.getOrDefault(dt.code(), 0L).intValue()))
                .toList();
    }

    /** "Show me transformer DT-003" */
    @GetMapping("/{code}")
    public ApiModels.Transformer get(@PathVariable String code) {
        PortalModels.Dt dt = findTransformer(code);
        return ApiMapper.toTransformer(dt, metersUnder(dt.code()).size());
    }

    /** "Which meters sit under transformer DT-003?" (the portal can't answer this directly) */
    @GetMapping("/{code}/meters")
    public List<ApiModels.Meter> meters(@PathVariable String code) {
        PortalModels.Dt dt = findTransformer(code);
        return metersUnder(dt.code()).stream().map(ApiMapper::toMeter).toList();
    }

    private PortalModels.Dt findTransformer(String code) {
        PortalModels.Dt dt = store.current().transformersByCode().get(code.trim().toUpperCase(Locale.ROOT));
        if (dt == null) {
            throw new NotFoundException("No transformer with code " + code);
        }
        return dt;
    }

    private List<PortalModels.Meter> metersUnder(String dtCode) {
        return store.current().meters().stream()
                .filter(m -> dtCode.equalsIgnoreCase(m.dtCode()))
                .toList();
    }
}
