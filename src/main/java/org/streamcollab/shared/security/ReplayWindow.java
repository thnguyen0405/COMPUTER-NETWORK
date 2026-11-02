package org.streamcollab.shared.security;

import java.util.BitSet;

/** Bounded sliding anti-replay window. Called only AFTER successful GCM authentication. */
final class ReplayWindow {
    private final int width;
    private BitSet seen;
    private long largest = -1;

    ReplayWindow(int width) {
        if (width < 64 || width > 4096) throw new IllegalArgumentException("Invalid replay window");
        this.width = width;
        this.seen = new BitSet(width);
    }

    boolean accept(long number) {
        if (number < 0) return false;
        if (largest == -1) {
            largest = number;
            seen.set(0);
            return true;
        }
        if (number > largest) {
            long shift = number - largest;
            BitSet shifted = new BitSet(width);
            if (shift < width) {
                for (int i = seen.nextSetBit(0); i >= 0; i = seen.nextSetBit(i + 1)) {
                    int newIndex = i + (int) shift;
                    if (newIndex < width) shifted.set(newIndex);
                }
            }
            shifted.set(0);
            seen = shifted;
            largest = number;
            return true;
        }
        long distance = largest - number;
        if (distance >= width || seen.get((int) distance)) return false;
        seen.set((int) distance);
        return true;
    }

    void clear() {
        seen.clear();
        largest = -1;
    }
}
