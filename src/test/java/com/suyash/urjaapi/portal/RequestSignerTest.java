package com.suyash.urjaapi.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestSignerTest {

    /**
     * Known-good sample captured from the browser's Network tab when clicking "Export all meters":
     * x-timestamp: 1792014827, x-signature: 1b2e13a2...1874bc4, with the secret from /portal/keys.
     * If our signer reproduces the browser's signature exactly, the recipe is right.
     */
    @Test
    void reproducesTheSignatureTheBrowserSent() {
        String signature = RequestSigner.sign(
                "I3dZPPf5CgTp7JyGNMI8i6z8LFR7TmSR", "GET", "/portal/export", "page=1", 1792014827L);

        assertEquals("1b2e13a26974f29d5b7f8418cd971e35e2e4f69a0dfba2bd030db3bda1874bc4", signature);
    }
}
