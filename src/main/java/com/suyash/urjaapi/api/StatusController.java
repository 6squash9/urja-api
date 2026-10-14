package com.suyash.urjaapi.api;

import com.suyash.urjaapi.store.MeterStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Is the service ready, and how fresh is the data?" Useful because meters/transformers come from a cache. */
@RestController
public class StatusController {

    private final MeterStore store;

    public StatusController(MeterStore store) {
        this.store = store;
    }

    @GetMapping("/api/status")
    public ApiModels.Status status() {
        return store.currentIfLoaded()
                .map(s -> new ApiModels.Status(true, s.loadedAt().toString(), s.meters().size(),
                        s.transformers().size(), store.lastError()))
                .orElseGet(() -> new ApiModels.Status(false, null, 0, 0, store.lastError()));
    }
}
