package org.streamcollab.unit;

import org.streamcollab.shared.security.PeerRole;
import org.streamcollab.shared.security.UdpSecurityException;
import org.streamcollab.shared.security.UdpSessionCrypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** No third-party test framework, run using scripts/test-member3-stage3.sh. */
public final class UdpSessionCryptoSmokeTest {
    private static int assertions;
    private static final byte[] H = "version=1;session=demo;packet=...".getBytes(StandardCharsets.UTF_8);
    private static final byte[] P = "member3:hello".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        byte[] secret = UdpSessionCrypto.newSessionSecret();
        check(secret.length == 32, "new secret has 256 bits");
        byte[] otherSecret = UdpSessionCrypto.newSessionSecret();
        check(!Arrays.equals(secret, otherSecret), "fresh secrets differ");
        try (UdpSessionCrypto client = UdpSessionCrypto.install(secret, "demo-session", PeerRole.CLIENT);
             UdpSessionCrypto server = UdpSessionCrypto.install(secret, "demo-session", PeerRole.SERVER)) {
            byte[] ciphertext = client.seal(0, H, P);
            check(ciphertext.length == P.length + 16, "GCM tag 16 bytes");
            check(!Arrays.equals(ciphertext, P), "ciphertext is not plaintext");
            check(Arrays.equals(server.open(0, H, ciphertext), P), "client to server roundtrip");
            failCode(() -> server.open(0, H, ciphertext), "REPLAY_REJECTED");
            byte[] reply = server.seal(0, H, P);
            check(Arrays.equals(client.open(0, H, reply), P), "server to client roundtrip");
            failCode(() -> client.seal(0, H, P), "PACKET_NUMBER_REUSED");
            failCode(() -> client.seal(-1, H, P), "PACKET_NUMBER_REUSED");
            failCode(() -> client.seal(0, H, P), "PACKET_NUMBER_REUSED");
            failCode(() -> client.open(0, H, reply), "REPLAY_REJECTED");
            byte[] samePayloadRetry = client.seal(1, H, P);
            check(!Arrays.equals(ciphertext, samePayloadRetry), "same payload new packet => new ciphertext");
            check(Arrays.equals(server.open(1, H, samePayloadRetry), P), "fresh retransmission accepted");
            byte[] n5 = client.seal(5, H, P);
            check(Arrays.equals(server.open(5, H, n5), P), "sender may skip numbers");
            failCode(() -> client.seal(4, H, P), "PACKET_NUMBER_REUSED");

            byte[] changedHeader = Arrays.copyOf(H, H.length);
            changedHeader[0] ^= 1;
            byte[] n6 = client.seal(6, H, P);
            failCode(() -> server.open(6, changedHeader, n6), "AUTHENTICATION_FAILED");
            check(Arrays.equals(server.open(6, H, n6), P), "bad header cannot poison replay window");
            byte[] n7 = client.seal(7, H, P);
            byte[] tampered = Arrays.copyOf(n7, n7.length);
            tampered[0] ^= 1;
            failCode(() -> server.open(7, H, tampered), "AUTHENTICATION_FAILED");
            check(Arrays.equals(server.open(7, H, n7), P), "bad ciphertext cannot poison window");
            byte[] n8 = client.seal(8, H, P);
            failCode(() -> server.open(9, H, n8), "AUTHENTICATION_FAILED");
            check(Arrays.equals(server.open(8, H, n8), P), "packet number authenticated");
            byte[] shortTag = new byte[15];
            failCode(() -> server.open(10, H, shortTag), "INVALID_PACKET");
            failCode(() -> server.open(-1, H, n8), "INVALID_PACKET");
            failCode(() -> client.seal(9, new byte[0], P), "INVALID_PACKET_SIZE");
            failCode(() -> client.seal(9, H, new byte[1200]), "INVALID_PACKET_SIZE");
            failCode(() -> server.open(10, H, new byte[1200]), "INVALID_PACKET_SIZE");
            byte[] maxPacket = new byte[1200 - H.length - 16];
            byte[] maxCipher = client.seal(9, H, maxPacket);
            check(maxCipher.length + H.length == 1200, "1200 byte MTU boundary");
            check(Arrays.equals(server.open(9, H, maxCipher), maxPacket), "MTU-size packet decrypts");
        }
        try (UdpSessionCrypto a = UdpSessionCrypto.install(secret, "sid2", PeerRole.CLIENT);
             UdpSessionCrypto b = UdpSessionCrypto.install(secret, "sid2", PeerRole.SERVER);
             UdpSessionCrypto wrongSid = UdpSessionCrypto.install(secret, "sid3", PeerRole.SERVER);
             UdpSessionCrypto wrongSecret = UdpSessionCrypto.install(otherSecret, "sid2", PeerRole.SERVER);
             UdpSessionCrypto wrongRole = UdpSessionCrypto.install(secret, "sid2", PeerRole.CLIENT)) {
            byte[] n0 = a.seal(0, H, P);
            failCode(() -> wrongSid.open(0, H, n0), "AUTHENTICATION_FAILED");
            failCode(() -> wrongSecret.open(0, H, n0), "AUTHENTICATION_FAILED");
            failCode(() -> wrongRole.open(0, H, n0), "AUTHENTICATION_FAILED");
            check(Arrays.equals(b.open(0, H, n0), P), "valid peer unaffected by failed peers");
        }
        try (UdpSessionCrypto tx = UdpSessionCrypto.install(secret, "reorder", PeerRole.CLIENT);
             UdpSessionCrypto rx = UdpSessionCrypto.install(secret, "reorder", PeerRole.SERVER, 64)) {
            byte[] zero = tx.seal(0, H, P);
            byte[] one = tx.seal(1, H, P);
            byte[] two = tx.seal(2, H, P);
            check(Arrays.equals(rx.open(2, H, two), P), "future packet accepted");
            check(Arrays.equals(rx.open(0, H, zero), P), "out of order within window accepted");
            check(Arrays.equals(rx.open(1, H, one), P), "second out of order accepted");
            failCode(() -> rx.open(1, H, one), "REPLAY_REJECTED");
            byte[] high = tx.seal(100, H, P);
            check(Arrays.equals(rx.open(100, H, high), P), "jump forward accepted");
            failCode(() -> rx.open(0, H, zero), "REPLAY_REJECTED");
            byte[] high2 = tx.seal(10000, H, P);
            check(Arrays.equals(rx.open(10000, H, high2), P), "large jump bounded");
            failCode(() -> rx.open(100, H, high), "REPLAY_REJECTED");
        }
        try (UdpSessionCrypto tx = UdpSessionCrypto.install(secret, "test-reset", PeerRole.CLIENT)) {
            byte[] data = tx.seal(0, H, P);
            try (UdpSessionCrypto rx = UdpSessionCrypto.install(secret, "test-reset", PeerRole.SERVER)) {
                check(Arrays.equals(rx.open(0, H, data), P), "fresh receive context");
            }
            tx.close();
            tx.close();
            failIllegalState(() -> tx.seal(1, H, P));
        }
        checkThrowsIllegal(() -> UdpSessionCrypto.install(new byte[4], "sid", PeerRole.CLIENT));
        checkThrowsIllegal(() -> UdpSessionCrypto.install(secret, " ", PeerRole.CLIENT));
        checkThrowsIllegal(() -> UdpSessionCrypto.install(secret, "sid", PeerRole.CLIENT, 1));
        byte[] malformed = new byte[130];
        Arrays.fill(malformed, (byte) 'X');
        checkThrowsIllegal(() -> UdpSessionCrypto.install(secret,
                new String(malformed, StandardCharsets.US_ASCII), PeerRole.CLIENT));
        // The caller may clear their original copy without corrupting derived keys.
        byte[] secretCopy = Arrays.copyOf(secret, secret.length);
        try (UdpSessionCrypto tx = UdpSessionCrypto.install(secretCopy, "copy", PeerRole.CLIENT);
             UdpSessionCrypto rx = UdpSessionCrypto.install(secret, "copy", PeerRole.SERVER)) {
            Arrays.fill(secretCopy, (byte) 0);
            check(Arrays.equals(rx.open(0, H, tx.seal(0, H, P)), P), "input secret retained only via derived keys");
        }
        Arrays.fill(secret, (byte) 0);
        Arrays.fill(otherSecret, (byte) 0);
        System.out.println("PASS: " + assertions + " Member 3 stage 3 AES-GCM and replay checks");
    }

    private static void check(boolean truth, String message) {
        if (!truth) throw new AssertionError(message);
        assertions++;
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static void failCode(Checked fn, String expected) throws Exception {
        try {
            fn.run();
        } catch (UdpSecurityException e) {
            check(expected.equals(e.code()), "expected " + expected + " got " + e.code());
            return;
        }
        throw new AssertionError("Expected " + expected);
    }

    private static void failIllegalState(Checked fn) throws Exception {
        try { fn.run(); }
        catch (IllegalStateException e) { check(true, "closed state"); return; }
        throw new AssertionError("Expected IllegalStateException");
    }

    private static void checkThrowsIllegal(Checked fn) throws Exception {
        try { fn.run(); }
        catch (IllegalArgumentException e) { check(true, "invalid argument"); return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
