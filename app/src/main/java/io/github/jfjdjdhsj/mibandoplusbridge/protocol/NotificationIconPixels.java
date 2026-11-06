// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Watch pixel layouts from the icon request. Pixels are packed ARGB, row by row. */
public final class NotificationIconPixels {
    public static final int RGB_565_LE = 0;
    public static final int RGB_565_BE = 1;
    public static final int XRGB_8888_LE = 2;
    public static final int ARGB_8888_LE = 3;
    public static final int ARGB_8565_LE = 7;
    public static final int ABGR_8565_LE = 8;

    private NotificationIconPixels() {}

    public static byte[] encode(int[] argb, int width, int height, int format) {
        if (width < 1 || height < 1 || width > 256 || height > 256
                || argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("ICON_BITMAP");
        }
        return switch (format) {
            case RGB_565_LE -> rgb565(argb, ByteOrder.LITTLE_ENDIAN);
            case RGB_565_BE -> rgb565(argb, ByteOrder.BIG_ENDIAN);
            case XRGB_8888_LE, ARGB_8888_LE -> argb8888(argb);
            case ARGB_8565_LE -> rgb8565(argb, false);
            case ABGR_8565_LE -> rgb8565(argb, true);
            default -> throw new IllegalArgumentException("ICON_PIXEL_FORMAT");
        };
    }

    private static byte[] rgb565(int[] argb, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(argb.length * 2).order(order);
        for (int pixel : argb) {
            int red = (pixel >> 16) & 0xff;
            int green = (pixel >> 8) & 0xff;
            int blue = pixel & 0xff;
            int packed = ((red >> 3) << 11) | ((green >> 2) << 5) | (blue >> 3);
            buffer.putShort((short) packed);
        }
        return buffer.array();
    }

    private static byte[] argb8888(int[] argb) {
        ByteBuffer buffer = ByteBuffer.allocate(argb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int pixel : argb) buffer.putInt(pixel);
        return buffer.array();
    }

    private static byte[] rgb8565(int[] argb, boolean swapRedBlue) {
        ByteBuffer buffer = ByteBuffer.allocate(argb.length * 3).order(ByteOrder.LITTLE_ENDIAN);
        for (int pixel : argb) {
            int alpha = (pixel >> 24) & 0xff;
            int red = (pixel >> 19) & 0x1f;
            int green = (pixel >> 10) & 0x3f;
            int blue = (pixel >> 3) & 0x1f;
            int packed = ((swapRedBlue ? blue : red) << 11) | (green << 5) | (swapRedBlue ? red : blue);
            buffer.putShort((short) packed);
            buffer.put((byte) alpha);
        }
        return buffer.array();
    }
}
