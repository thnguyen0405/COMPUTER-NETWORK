package org.streamcollab.client.httpbridge;

/** A safe, public local-HTTP API error (never expose a raw exception to the browser). */
public final class ApiProblem extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean retryable;
    public ApiProblem(int status, String code, boolean retryable) {
        super(code);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }
    public int status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public static ApiProblem invalid() { return new ApiProblem(400, "INVALID_REQUEST", false); }
}
