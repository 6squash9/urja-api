package com.suyash.urjaapi.api;

import com.suyash.urjaapi.portal.PortalException;
import com.suyash.urjaapi.store.CacheNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/** Turns exceptions into clean JSON errors: {"error": "...", "message": "..."}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiModels.ApiError notFound(NotFoundException e) {
        return new ApiModels.ApiError("not_found", e.getMessage());
    }

    /** First portal download not finished / failed. 503 = "try again shortly". */
    @ExceptionHandler(CacheNotReadyException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiModels.ApiError notReady(CacheNotReadyException e) {
        return new ApiModels.ApiError("not_ready", e.getMessage());
    }

    /** The portal itself failed or answered something unexpected. 502 = "the system behind me failed". */
    @ExceptionHandler({PortalException.class, RestClientException.class})
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiModels.ApiError portalError(RuntimeException e) {
        log.warn("Portal call failed: {}", e.getMessage());
        return new ApiModels.ApiError("portal_error", "The Urja portal request failed: " + e.getMessage());
    }
}
