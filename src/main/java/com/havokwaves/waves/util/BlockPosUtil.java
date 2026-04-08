package com.havokwaves.waves.util;

public final class BlockPosUtil {
    private static final int XZ_MASK = 0x3FFFFFF;
    private static final int Y_MASK = 0xFFF;
    private static final int XZ_SIGN_BIT = 0x2000000;

    private BlockPosUtil() {
    }

    public static long pack(final int x, final int y, final int z) {
        return ((long) (x & XZ_MASK) << 38)
                | ((long) (z & XZ_MASK) << 12)
                | (y & Y_MASK);
    }

    public static int unpackX(final long packed) {
        int x = (int) (packed >> 38);
        if ((x & XZ_SIGN_BIT) != 0) {
            x |= ~XZ_MASK;
        }
        return x;
    }

    public static int unpackY(final long packed) {
        int y = (int) (packed & Y_MASK);
        if (y >= 2048) {
            y -= 4096;
        }
        return y;
    }

    public static int unpackZ(final long packed) {
        int z = (int) ((packed >> 12) & XZ_MASK);
        if ((z & XZ_SIGN_BIT) != 0) {
            z |= ~XZ_MASK;
        }
        return z;
    }
}
