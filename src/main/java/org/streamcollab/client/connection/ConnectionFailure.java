package org.streamcollab.client.connection;

/** Public-safe error code (not raw I/O exception text or credential material). */
public final class ConnectionFailure extends Exception {
    private final String code;
    public ConnectionFailure(String code) {
        super("Connection failed: " + validate(code));
        this.code = code;
    }
    private static String validate(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Error code must be uppercase and contain no secrets");
        }
        return code;
    }
    public String code() { return code; }
}
