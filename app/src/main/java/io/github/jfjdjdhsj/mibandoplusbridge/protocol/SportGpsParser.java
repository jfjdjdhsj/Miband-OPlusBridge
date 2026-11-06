// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Xiaomi GPS file (sport flag set, detail type 2) to the track string OPPO Health
 * draws when a band record's version is 2. Points stay WGS84; the health map
 * converts them for China because the device type is a band.
 */
public final class SportGpsParser {
    private static final int MAX_POINTS = 1500;

    private SportGpsParser() {}

    public static Track parse(byte[] bytes) {
        if (bytes == null || bytes.length < 16) throw unsupported("UNSUPPORTED_SPORT_FILE");
        int descriptor = bytes[6] & 0xff;
        if ((descriptor & 0x80) == 0 || (descriptor & 3) != 2) throw unsupported("UNSUPPORTED_SPORT_FILE");
        int activity = (descriptor & 0x7f) >>> 2;
        if (activity != 22 && activity != 23) throw unsupported("UNSUPPORTED_SPORT_FILE");
        int version = bytes[5] & 0xff;
        if (version == 2) return parseFixedStride(bytes, 1, 4, 2);
        int validityBytes = version >= 5 ? 2 : 1;
        if (version < 3 || version > 5) throw unsupported("UNSUPPORTED_SPORT_FILE");
        if (activity == 23 && version > 4) throw unsupported("UNSUPPORTED_SPORT_FILE");
        int cursor = 8 + validityBytes;
        int end = bytes.length - 4;
        if (cursor > end) throw unsupported("UNSUPPORTED_SPORT_FILE");
        boolean time = flag(bytes, 0);
        boolean longitude = flag(bytes, 1);
        boolean latitude = flag(bytes, 2);
        if (!longitude || !latitude) throw unsupported("UNSUPPORTED_SPORT_FILE");
        boolean accuracy = flag(bytes, 3);
        boolean speed = flag(bytes, 4);
        boolean source = flag(bytes, 5);
        boolean altitude = flag(bytes, 6);
        boolean hdop = flag(bytes, 7);
        boolean runway = version >= 5 && flag(bytes, 8);
        int stride = (time ? 4 : 0) + 8 + (accuracy ? 4 : 0) + (speed || source ? 2 : 0)
                + (altitude ? 4 : 0) + (hdop ? 4 : 0) + (runway ? 1 : 0);
        int count = Integer.MAX_VALUE;
        if (version >= 4) {
            if (cursor > end - 4) throw unsupported("UNSUPPORTED_SPORT_FILE");
            count = (int) Integer.toUnsignedLong(leInt(bytes, cursor));
            cursor += 4;
            if (count < 0 || count > 100_000) throw unsupported("UNSUPPORTED_SPORT_FILE");
        }
        long headerMs = Integer.toUnsignedLong(leInt(bytes, 0)) * 1000L;
        List<double[]> points = new ArrayList<>();
        long first = 0;
        long last = 0;
        for (int i = 0; i < count && cursor <= end - stride; i++) {
            long pointMs = headerMs;
            int at = cursor;
            if (time) {
                pointMs = Integer.toUnsignedLong(leInt(bytes, at)) * 1000L;
                at += 4;
            }
            float lon = leFloat(bytes, at);
            at += 4;
            float lat = leFloat(bytes, at);
            cursor += stride;
            if (!Float.isFinite(lat) || !Float.isFinite(lon) || lat < -90f || lat > 90f || lon < -180f || lon > 180f) {
                continue;
            }
            if (lat == 0 && lon == 0) continue;
            if (points.isEmpty()) first = pointMs;
            last = pointMs;
            points.add(new double[] {lat, lon, pointMs});
        }
        if (points.size() < 2) throw unsupported("UNSUPPORTED_SPORT_FILE");
        long start = headerMs > 0 ? headerMs : first;
        long stop = Math.max(start + 1000L, Math.max(last, first));
        List<double[]> kept = downsample(points);
        return new Track(start, stop, kept.size(), payload(kept));
    }

    /**
     * Band 8 Pro GPS v2. One flag byte, then points until the CRC.
     * After time, longitude and latitude the tail is a speed float in metres per
     * second and a uint16. The top three flag bits are set on a real file and are not more fields.
     */
    private static Track parseFixedStride(byte[] bytes, int validityBytes, int speedBytes, int tailBytes) {
        int end = bytes.length - 4;
        int cursor = 8 + validityBytes;
        if (cursor > end) throw unsupported("UNSUPPORTED_SPORT_FILE");
        boolean time = flag(bytes, 0);
        if (!flag(bytes, 1) || !flag(bytes, 2)) throw unsupported("UNSUPPORTED_SPORT_FILE");
        boolean speed = flag(bytes, 3);
        int stride = (time ? 4 : 0) + 8 + (speed ? speedBytes : 0) + (flag(bytes, 4) ? tailBytes : 0);
        long headerMs = Integer.toUnsignedLong(leInt(bytes, 0)) * 1000L;
        List<double[]> points = new ArrayList<>();
        long first = 0;
        long last = 0;
        while (cursor <= end - stride) {
            long pointMs = headerMs;
            int at = cursor;
            if (time) {
                pointMs = Integer.toUnsignedLong(leInt(bytes, at)) * 1000L;
                at += 4;
            }
            float lon = leFloat(bytes, at);
            at += 4;
            float lat = leFloat(bytes, at);
            at += 4;
            double metersPerSecond = 0;
            if (speed && speedBytes == 4) {
                float reading = leFloat(bytes, at);
                if (Float.isFinite(reading) && reading > 0f && reading < 80f) metersPerSecond = reading;
            }
            cursor += stride;
            if (!Float.isFinite(lat) || !Float.isFinite(lon) || lat < -90f || lat > 90f || lon < -180f || lon > 180f) {
                continue;
            }
            if (lat == 0 && lon == 0) continue;
            if (points.isEmpty()) first = pointMs;
            last = pointMs;
            points.add(new double[] {lat, lon, pointMs, metersPerSecond});
        }
        if (points.size() < 2) throw unsupported("UNSUPPORTED_SPORT_FILE");
        long start = headerMs > 0 ? headerMs : first;
        long stop = Math.max(start + 1000L, Math.max(last, first));
        List<double[]> kept = downsample(points);
        return new Track(start, stop, kept.size(), payload(kept));
    }

    /**
     * Pace samples for the Health speed chart. The chart key is {@code pace},
     * and each value is seconds per kilometre. A leading 2 is the field count.
     */
    public static String paceSeries(String gps) {
        if (gps == null || !gps.startsWith("5,")) return null;
        String[] part = gps.split(",");
        int stride;
        try {
            stride = Integer.parseInt(part[0]);
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (stride < 5 || (part.length - 1) % stride != 0) return null;
        double[] speeds = new double[(part.length - 1) / stride];
        int count = 0;
        for (int i = 1 + stride; i + 4 < part.length; i += stride) {
            double speed = speedAt(part, i + 4);
            if (speed > 0) speeds[count++] = speed;
        }
        if (count < 2) return null;
        double[] ordered = Arrays.copyOf(speeds, count);
        Arrays.sort(ordered);
        // One GPS spike of 40 m/s becomes 144 km/h and stretches the chart until the ride disappears.
        double cap = Math.max(ordered[count / 2] * 4d, 6d);
        StringBuilder body = new StringBuilder("2,0,0");
        int points = 0;
        for (int i = 1 + stride; i + 4 < part.length; i += stride) {
            double speed = speedAt(part, i + 4);
            if (speed < 0.3d || speed > cap) continue;
            int pace = (int) Math.round(1000.0d / speed);
            if (pace < 30 || pace > 7200) continue;
            body.append(',').append(part[i]).append(',').append(pace);
            points++;
        }
        return points < 2 ? null : body.toString();
    }

    private static double speedAt(String[] part, int index) {
        try {
            return Double.parseDouble(part[index]);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    /** Fastest plausible pace in a {@link #paceSeries} string, or null. */
    public static Integer fastestPace(String pace) {
        if (pace == null || !pace.startsWith("2,")) return null;
        String[] part = pace.split(",");
        int best = Integer.MAX_VALUE;
        for (int i = 4; i < part.length; i += 2) {
            try {
                int value = Integer.parseInt(part[i]);
                if (value >= 30 && value < best) best = value;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return best == Integer.MAX_VALUE ? null : best;
    }

    /** OPPO band track: a leading 5 says each point is time, latitude, longitude, pause, speed. */
    public static String payload(List<double[]> points) {
        StringBuilder body = new StringBuilder("5,0,0,0,0,0");
        for (double[] point : points) {
            body.append(',')
                    .append((long) point[2]).append(',')
                    .append(String.format(Locale.US, "%.6f", point[0])).append(',')
                    .append(String.format(Locale.US, "%.6f", point[1]))
                    .append(",0,")
                    .append(String.format(Locale.US, "%.2f", point.length > 3 ? point[3] : 0));
        }
        return body.toString();
    }

    private static List<double[]> downsample(List<double[]> points) {
        if (points.size() <= MAX_POINTS) return points;
        List<double[]> kept = new ArrayList<>(MAX_POINTS);
        double step = (points.size() - 1) / (double) (MAX_POINTS - 1);
        for (int i = 0; i < MAX_POINTS; i++) kept.add(points.get((int) Math.round(i * step)));
        return kept;
    }

    private static boolean flag(byte[] bytes, int index) {
        return (bytes[8 + index / 8] & (1 << (7 - (index % 8)))) != 0;
    }

    private static int leInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static float leFloat(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private static IllegalArgumentException unsupported(String status) {
        return new IllegalArgumentException(status);
    }

    public static final class Track {
        public final long startMs;
        public final long endMs;
        public final int points;
        public final String payload;

        Track(long startMs, long endMs, int points, String payload) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.points = points;
            this.payload = payload;
        }
    }
}
