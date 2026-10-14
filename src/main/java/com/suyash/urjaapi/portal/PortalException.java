package com.suyash.urjaapi.portal;

/** Something went wrong while talking to the Urja portal (login failed, bad response, etc.). */
public class PortalException extends RuntimeException {

    public PortalException(String message) {
        super(message);
    }
}
