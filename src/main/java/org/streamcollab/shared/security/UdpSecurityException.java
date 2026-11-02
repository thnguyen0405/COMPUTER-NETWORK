package org.streamcollab.shared.security;

import java.security.GeneralSecurityException;

/** Deliberately generic network-safe error codes; never contains key/plaintext. */
public final class UdpSecurityException extends GeneralSecurityException {
    private final String code;

    UdpSecurityException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
