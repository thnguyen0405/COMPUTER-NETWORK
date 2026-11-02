package org.streamcollab.shared.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** RFC 5869 HKDF based on HmacSHA256. Internal, not a wire protocol. */
final class HkdfSha256 {
    private HkdfSha256() { }

    static byte[] extract(byte[] salt, byte[] inputKeyMaterial) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(inputKeyMaterial);
    }

    static byte[] expand(byte[] pseudorandomKey, byte[] info, int length)
            throws GeneralSecurityException {
        if (length < 1 || length > 255 * 32) {
            throw new IllegalArgumentException("Invalid HKDF output length");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pseudorandomKey, "HmacSHA256"));
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int pos = 0;
        try {
            for (int block = 1; pos < length; block++) {
                mac.update(previous);
                mac.update(info);
                mac.update((byte) block);
                byte[] next = mac.doFinal();
                Arrays.fill(previous, (byte) 0);
                previous = next;
                int count = Math.min(next.length, length - pos);
                System.arraycopy(next, 0, result, pos, count);
                pos += count;
            }
            return result;
        } finally {
            Arrays.fill(previous, (byte) 0);
        }
    }
}
