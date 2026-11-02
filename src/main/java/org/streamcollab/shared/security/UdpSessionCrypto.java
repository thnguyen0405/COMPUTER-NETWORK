package org.streamcollab.shared.security;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Stage 3 security module: directional AES-256-GCM with packet-number nonces,
 * authenticated header bytes and a bounded anti-replay window.
 *
 * NO UDP header serialization is performed here: Member 1 owns wire framing.
 * Header MUST include session ID and packet number per the shared protocol.
 * Packet number supplied by transport MUST equal the value in header.
 *
 * Construct once per fresh session and peer. Never reinstall the same secret,
 * session ID and role in a second active sender (nonce reuse risk).
 */
public final class UdpSessionCrypto implements AutoCloseable {
    public static final int MAX_DATAGRAM_BYTES = 1200;
    public static final int GCM_TAG_BYTES = 16;
    public static final int DEFAULT_REPLAY_WINDOW = 256;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_PREFIX_BYTES = 4;
    private static final int NONCE_BYTES = 12;
    private static final byte[] CONTEXT =
            "StreamCollab/UDP-AES-256-GCM/v1".getBytes(StandardCharsets.US_ASCII);

    private final byte[] sendKey;
    private final byte[] receiveKey;
    private final byte[] sendPrefix;
    private final byte[] receivePrefix;
    private final ReplayWindow replay;
    private long lastSent = -1;
    private boolean closed;

    /** Create fresh high-entropy material to distribute ONLY via verified TLS. */
    public static byte[] newSessionSecret() {
        byte[] secret = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /**
     * The shared secret is 32+ high-entropy bytes obtained over verified TLS.
     * Both peers use the same secret and sessionId but different PeerRole.
     */
    public static UdpSessionCrypto install(byte[] sharedSecret, String sessionId, PeerRole role)
            throws GeneralSecurityException {
        return install(sharedSecret, sessionId, role, DEFAULT_REPLAY_WINDOW);
    }

    public static UdpSessionCrypto install(byte[] sharedSecret, String sessionId,
                                          PeerRole role, int replayWindow)
            throws GeneralSecurityException {
        Objects.requireNonNull(sharedSecret, "sharedSecret");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(role, "role");
        if (replayWindow < 64 || replayWindow > 4096) {
            throw new IllegalArgumentException("Invalid replay window");
        }
        if (sharedSecret.length < KEY_BYTES || sharedSecret.length > 128) {
            throw new IllegalArgumentException("Session secret must contain 32..128 high-entropy bytes");
        }
        byte[] id = sessionId.getBytes(StandardCharsets.UTF_8);
        if (id.length < 1 || id.length > 128 || sessionId.isBlank()) {
            throw new IllegalArgumentException("Invalid session identifier");
        }
        // Salt binds derived keys to both protocol version and session identifier.
        ByteBuffer context = ByteBuffer.allocate(CONTEXT.length + 4 + id.length);
        context.put(CONTEXT).putInt(id.length).put(id);
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(context.array());
        byte[] prk = HkdfSha256.extract(salt, sharedSecret);
        try {
            byte[] c2sKey = expandLabel(prk, "key/client-to-server", KEY_BYTES);
            byte[] s2cKey = expandLabel(prk, "key/server-to-client", KEY_BYTES);
            byte[] c2sPrefix = expandLabel(prk, "nonce/client-to-server", NONCE_PREFIX_BYTES);
            byte[] s2cPrefix = expandLabel(prk, "nonce/server-to-client", NONCE_PREFIX_BYTES);
            return role == PeerRole.CLIENT
                    ? new UdpSessionCrypto(c2sKey, s2cKey, c2sPrefix, s2cPrefix, replayWindow)
                    : new UdpSessionCrypto(s2cKey, c2sKey, s2cPrefix, c2sPrefix, replayWindow);
        } finally {
            Arrays.fill(prk, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    private static byte[] expandLabel(byte[] prk, String name, int length)
            throws GeneralSecurityException {
        byte[] info = ("StreamCollab/UDP-AES-256-GCM/v1/" + name)
                .getBytes(StandardCharsets.US_ASCII);
        return HkdfSha256.expand(prk, info, length);
    }

    private UdpSessionCrypto(byte[] sendKey, byte[] receiveKey,
                             byte[] sendPrefix, byte[] receivePrefix, int replayWindow) {
        this.sendKey = sendKey;
        this.receiveKey = receiveKey;
        this.sendPrefix = sendPrefix;
        this.receivePrefix = receivePrefix;
        this.replay = new ReplayWindow(replayWindow);
    }

    /**
     * Returns ciphertext || 16-byte GCM tag. Packet header is separate, as AAD.
     * A retry MUST use a new packet number even for the same plaintext.
     */
    public synchronized byte[] seal(long packetNumber, byte[] header, byte[] plaintext)
            throws GeneralSecurityException {
        checkOpen();
        Objects.requireNonNull(plaintext, "plaintext");
        validateHeaderAndSize(header, plaintext.length + GCM_TAG_BYTES);
        if (packetNumber < 0 || packetNumber <= lastSent) {
            throw new UdpSecurityException("PACKET_NUMBER_REUSED");
        }
        Cipher cipher = initCipher(Cipher.ENCRYPT_MODE, sendKey, sendPrefix, packetNumber);
        cipher.updateAAD(numberAndHeader(packetNumber, header));
        byte[] ciphertext = cipher.doFinal(plaintext);
        lastSent = packetNumber; // state advances only for successful encryption
        return ciphertext;
    }

    /**
     * Authenticates before replay admission. A failed GCM tag cannot poison the
     * replay window; a valid duplicate is rejected before higher-layer ACKs.
     */
    public synchronized byte[] open(long packetNumber, byte[] header, byte[] ciphertextAndTag)
            throws GeneralSecurityException {
        checkOpen();
        Objects.requireNonNull(ciphertextAndTag, "ciphertextAndTag");
        validateHeaderAndSize(header, ciphertextAndTag.length);
        if (ciphertextAndTag.length < GCM_TAG_BYTES || packetNumber < 0) {
            throw new UdpSecurityException("INVALID_PACKET");
        }
        Cipher cipher = initCipher(Cipher.DECRYPT_MODE, receiveKey, receivePrefix, packetNumber);
        cipher.updateAAD(numberAndHeader(packetNumber, header));
        final byte[] plaintext;
        try {
            plaintext = cipher.doFinal(ciphertextAndTag);
        } catch (AEADBadTagException invalidTag) {
            throw new UdpSecurityException("AUTHENTICATION_FAILED");
        }
        if (!replay.accept(packetNumber)) {
            Arrays.fill(plaintext, (byte) 0);
            throw new UdpSecurityException("REPLAY_REJECTED");
        }
        return plaintext;
    }

    private static byte[] numberAndHeader(long packetNumber, byte[] header) {
        ByteBuffer bytes = ByteBuffer.allocate(8 + header.length);
        bytes.putLong(packetNumber).put(header);
        return bytes.array();
    }

    private static Cipher initCipher(int mode, byte[] key, byte[] prefix, long packetNumber)
            throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_BYTES);
        ByteBuffer.wrap(nonce).putLong(NONCE_PREFIX_BYTES, packetNumber);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce));
        return cipher;
    }

    private static void validateHeaderAndSize(byte[] header, int encryptedBodySize)
            throws UdpSecurityException {
        Objects.requireNonNull(header, "header");
        if (header.length == 0 || header.length > MAX_DATAGRAM_BYTES
                || encryptedBodySize < 0
                || encryptedBodySize > MAX_DATAGRAM_BYTES - header.length) {
            throw new UdpSecurityException("INVALID_PACKET_SIZE");
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Crypto session closed");
    }

    /** Best-effort zeroization of application-owned key buffers; JVM cannot guarantee erasure. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Arrays.fill(sendKey, (byte) 0);
        Arrays.fill(receiveKey, (byte) 0);
        Arrays.fill(sendPrefix, (byte) 0);
        Arrays.fill(receivePrefix, (byte) 0);
        replay.clear();
    }
}
