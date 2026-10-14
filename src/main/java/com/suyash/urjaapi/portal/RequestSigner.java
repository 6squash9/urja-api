package com.suyash.urjaapi.portal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Reproduces how the portal signs the bulk export request.
 * Found in the portal's own JavaScript (Transformers page, function C):
 *
 *   message   = method + "\n" + path + "\n" + query + "\n" + unixSeconds
 *   signature = lowercase hex of HMAC-SHA256(key = signingSecret, data = message)
 *
 * The result goes in the x-signature header, the timestamp in x-timestamp.
 */
public final class RequestSigner {

    private RequestSigner() {
    }

    public static String sign(String secret, String method, String path, String query, long unixSeconds) {
        String message = String.join("\n", method, path, query, Long.toString(unixSeconds));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw); // lowercase hex, same as the browser
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute HMAC-SHA256 signature", e);
        }
    }
}
