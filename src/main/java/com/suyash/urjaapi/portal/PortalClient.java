package com.suyash.urjaapi.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The ONLY class that talks to the Urja portal ("the waiter who goes into the kitchen").
 *
 * What it knows (all discovered during the investigation, see PROTOCOL.md):
 *  - Login = form POST /login with email + password, plus an Origin header (SvelteKit CSRF check → 403 without it).
 *  - The portal answers with a session cookie that lives 1 hour (Max-Age=3600).
 *  - Every /portal/... call just needs that cookie. No cookie / expired cookie → 401.
 *  - The bulk export is signed: HMAC-SHA256 with a secret from /portal/keys (see RequestSigner).
 */
@Component
public class PortalClient {

    private static final Logger log = LoggerFactory.getLogger(PortalClient.class);
    private static final String SESSION_COOKIE = "__Secure-better-auth.session_token";
    private static final int MAX_PAGES = 1000; // safety stop so a buggy "total" can never loop forever

    private final RestClient http;
    private final String baseUrl;
    private final String email;
    private final String password;

    /** "name=value" of the session cookie. null until the first login. */
    private volatile String sessionCookie;

    public PortalClient(@Value("${portal.base-url}") String baseUrl,
                        @Value("${portal.email}") String email,
                        @Value("${portal.password}") String password) {
        this.baseUrl = baseUrl;
        this.email = email;
        this.password = password;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean hasCredentials() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }

    // ---------------------------------------------------------------- login / session

    /** Logs in and stores the session cookie. synchronized so two requests never log in at the same time. */
    public synchronized void login() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("email", email);
        form.add("password", password);

        ResponseEntity<String> response = http.post()
                .uri("/login")
                .header(HttpHeaders.ORIGIN, baseUrl) // required, or the portal answers 403 "Cross-site POST form submissions are forbidden"
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toEntity(String.class);

        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            setCookies = List.of();
        }
        sessionCookie = setCookies.stream()
                .filter(cookie -> cookie.startsWith(SESSION_COOKIE + "="))
                .map(cookie -> cookie.split(";", 2)[0]) // keep "name=value", drop "Max-Age=3600; Path=/; ..."
                .findFirst()
                .orElseThrow(() -> new PortalException(
                        "Login did not return a session cookie. Check PORTAL_EMAIL / PORTAL_PASSWORD."));
        log.info("Logged in to the portal");
    }

    /**
     * Runs a portal call with the session cookie.
     * If the portal answers 401 (session expired after ~1 hour), log in again and retry ONCE.
     */
    private <T> T withSession(Function<String, T> call) {
        if (sessionCookie == null) {
            login();
        }
        try {
            return call.apply(sessionCookie);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.info("Portal returned 401 (session expired?), logging in again and retrying once");
            login();
            return call.apply(sessionCookie);
        }
    }

    // ---------------------------------------------------------------- data

    /** All meters via the signed bulk export. Follows pages in case the export is ever split. */
    public List<PortalModels.Meter> fetchAllMeters() {
        List<PortalModels.Meter> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            PortalModels.ExportResponse response = fetchExportPage(page);
            List<PortalModels.Meter> data = response == null || response.data() == null ? List.of() : response.data();
            all.addAll(data);
            Integer total = response == null ? null : response.total();
            if (data.isEmpty() || total == null || all.size() >= total) {
                break;
            }
        }
        return all;
    }

    private PortalModels.ExportResponse fetchExportPage(int page) {
        return withSession(cookie -> {
            String secret = fetchSigningSecret(cookie);
            String query = "page=" + page;
            // Sign right before sending: the portal rejects stale timestamps.
            long timestamp = Instant.now().getEpochSecond();
            String signature = RequestSigner.sign(secret, "GET", "/portal/export", query, timestamp);
            return http.get()
                    .uri("/portal/export?" + query)
                    .header(HttpHeaders.COOKIE, cookie)
                    .header("x-timestamp", Long.toString(timestamp))
                    .header("x-signature", signature)
                    .retrieve()
                    .body(PortalModels.ExportResponse.class);
        });
    }

    private String fetchSigningSecret(String cookie) {
        PortalModels.KeysResponse keys = http.get()
                .uri("/portal/keys")
                .header(HttpHeaders.COOKIE, cookie)
                .retrieve()
                .body(PortalModels.KeysResponse.class);
        if (keys == null || keys.data() == null || keys.data().signingSecret() == null) {
            throw new PortalException("Portal did not return a signing secret from /portal/keys");
        }
        return keys.data().signingSecret();
    }

    /** All transformers, following /portal/dts pagination (20 per page). */
    public List<PortalModels.Dt> fetchAllTransformers() {
        List<PortalModels.Dt> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int currentPage = page;
            PortalModels.DtPage response = withSession(cookie -> http.get()
                    .uri("/portal/dts?page={page}", currentPage)
                    .header(HttpHeaders.COOKIE, cookie)
                    .retrieve()
                    .body(PortalModels.DtPage.class));
            List<PortalModels.Dt> data = response == null || response.data() == null ? List.of() : response.data();
            all.addAll(data);
            Integer total = response == null ? null : response.total();
            if (data.isEmpty() || total == null || all.size() >= total) {
                break;
            }
        }
        return all;
    }

    /** Raw consumption rows for one meter (always fetched live, not cached). */
    public List<Map<String, Object>> fetchEnergy(String meterId) {
        PortalModels.EnergyResponse response = withSession(cookie -> http.get()
                .uri("/portal/meters/{id}/energy", meterId)
                .header(HttpHeaders.COOKIE, cookie)
                .retrieve()
                .body(PortalModels.EnergyResponse.class));
        return response == null || response.data() == null ? List.of() : response.data();
    }
}
