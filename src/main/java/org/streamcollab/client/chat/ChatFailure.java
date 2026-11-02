package org.streamcollab.client.chat;

/** Safe error code for use at the eventual local HTTP error-mapping boundary. */
public final class ChatFailure extends RuntimeException {
    private final String code;
    public ChatFailure(String code) {
        super(code);
        this.code = code;
    }
    public String code() { return code; }
}
