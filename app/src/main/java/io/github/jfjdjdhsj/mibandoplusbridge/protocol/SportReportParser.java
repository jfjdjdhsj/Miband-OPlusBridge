// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Decodes one Xiaomi sport summary. Detail and GPS files stay unsupported:
 * OPPO health only needs the summary that becomes a single workout row.
 */
public final class SportReportParser {
    private SportReportParser() {}

    static Session parse(byte[] bytes) {
        if (bytes.length < 12) throw unsupported("UNSUPPORTED_SPORT_FILE");
        int descriptor = bytes[6] & 0xff;
        if ((descriptor & 0x80) == 0 || (descriptor & 3) != 1) throw unsupported("UNSUPPORTED_SPORT_FILE");
        int activity = (descriptor & 0x7f) >>> 2;
        int version = bytes[5] & 0xff;
        int schemaActivity = activity == 10 ? 9 : activity;
        SportReportLayouts.Layout layout = SportReportLayouts.layout(schemaActivity, version);
        if (layout == null) throw unsupported("UNSUPPORTED_SPORT_FILE");
        if (8 + layout.validityBytes > bytes.length - 4) throw unsupported("UNSUPPORTED_SPORT_FILE");
        Map<String, Long> values = read(bytes, layout);
        return session(activity, bytes, values);
    }

    private static Map<String, Long> read(byte[] bytes, SportReportLayouts.Layout layout) {
        boolean[] present = new boolean[layout.flags.length];
        for (int i = 0; i < layout.flags.length; i++) {
            int index = 8 + i / 8;
            present[i] = (bytes[index] & (1 << (7 - (i % 8)))) != 0;
        }
        Map<String, Integer> flagAt = new HashMap<>();
        for (int i = 0; i < layout.flags.length; i++) flagAt.put(layout.flags[i], i);
        Map<String, Long> values = new HashMap<>();
        int cursor = 8 + layout.validityBytes;
        int end = bytes.length - 4;
        for (SportReportLayouts.Field field : layout.fields) {
            if (!included(field, present, flagAt, values)) continue;
            if (cursor > end - field.bytes) throw unsupported("UNSUPPORTED_SPORT_FILE");
            values.put(field.name, field.floating ? 0L : unsigned(bytes, cursor, field.bytes));
            cursor += field.bytes;
        }
        return values;
    }

    private static boolean included(SportReportLayouts.Field field, boolean[] present,
            Map<String, Integer> flagAt, Map<String, Long> values) {
        if (field.gate != null) {
            Integer at = flagAt.get(field.gate);
            if (at == null || !present[at]) return false;
        }
        if (field.when == null) return true;
        Long actual = values.get(field.when);
        if (actual == null || field.values == null) return false;
        for (int allowed : field.values) if (actual == allowed) return true;
        return false;
    }

    private static long unsigned(byte[] bytes, int offset, int width) {
        long value = 0;
        for (int i = 0; i < width; i++) value |= (bytes[offset + i] & 0xffL) << (8 * i);
        return value;
    }

    private static Session session(int activity, byte[] bytes, Map<String, Long> values) {
        long headerSeconds = unsigned(bytes, 0, 4);
        Long startSeconds = values.get("startTime");
        long start = (startSeconds == null ? headerSeconds : startSeconds) * 1000L;
        Long endSeconds = values.get("endTime");
        Long duration = values.get("totalDuration");
        long end = endSeconds != null ? endSeconds * 1000L
                : duration == null ? start : start + duration * 1000L;
        if (start < 0 || end <= start || end - start > 48L * 3600_000L) {
            throw unsupported("INVALID_SPORT_TIMESTAMP");
        }
        Integer calories = first(values, "totalCalories", "activeCalories");
        Integer distance = narrow(values.get("totalDistance"));
        Integer steps = narrow(values.get("totalSteps"));
        Integer jumps = narrow(values.get("totalJumps"));
        Integer heart = narrow(values.get("avgHeartRate"));
        if (heart != null && (heart < 1 || heart > 250)) heart = null;
        Integer moving = narrow(duration);
        if (moving != null && (moving < 1 || moving > 48 * 3600)) moving = null;
        Integer bestPace = narrow(values.get("bestPace"));
        if (bestPace != null && (bestPace < 30 || bestPace > 7200)) bestPace = null;
        Mapped mapped = map(activity, narrow(values.get("subSportType")));
        if (mapped.mode != 3) bestPace = null;
        Integer reps = mapped.repsAreJumps ? jumps : steps;
        return new Session(mapped.mode, mapped.name, start, end, calories, distance, reps, heart,
                moving, bestPace);
    }

    private static Integer first(Map<String, Long> values, String primary, String fallback) {
        Integer value = narrow(values.get(primary));
        return value != null ? value : narrow(values.get(fallback));
    }

    private static Integer narrow(Long value) {
        if (value == null || value < 0 || value > Integer.MAX_VALUE) return null;
        return value.intValue();
    }

    private static Mapped map(int activity, Integer subSport) {
        return switch (activity) {
            case 22 -> foot(subSport);
            case 23 -> subSport == null || subSport == 6
                    ? new Mapped(3, "户外骑行", false) : unsupportedMapped();
            case 3 -> indoor(subSport);
            case 8 -> new Mapped(35, "自由活动", false);
            case 14 -> new Mapped(908, "跳绳", true);
            case 9 -> new Mapped(7, "泳池游泳", false);
            case 10 -> new Mapped(809, "开放水域游泳", false);
            default -> unsupportedMapped();
        };
    }

    private static Mapped foot(Integer subSport) {
        if (subSport == null) return unsupportedMapped();
        return switch (subSport) {
            case 1 -> new Mapped(2, "户外跑步", false);
            case 2, 207 -> new Mapped(1, "健走", false);
            case 4 -> new Mapped(36, "登山", false);
            case 5 -> new Mapped(37, "越野跑", false);
            case 15 -> new Mapped(506, "徒步", false);
            default -> unsupportedMapped();
        };
    }

    private static Mapped indoor(Integer subSport) {
        if (subSport == null) return unsupportedMapped();
        return switch (subSport) {
            case 3 -> new Mapped(10, "室内跑步", false);
            case 333 -> new Mapped(19, "室内健走", false);
            default -> unsupportedMapped();
        };
    }

    private static Mapped unsupportedMapped() {
        throw unsupported("UNSUPPORTED_SPORT_TYPE");
    }

    private static IllegalArgumentException unsupported(String status) {
        return new IllegalArgumentException(status);
    }

    public static String name(int mode) {
        return switch (mode) {
            case 2 -> "户外跑步";
            case 1 -> "健走";
            case 506 -> "徒步";
            case 3 -> "户外骑行";
            case 10 -> "室内跑步";
            case 35 -> "自由活动";
            case 908 -> "跳绳";
            case 7 -> "泳池游泳";
            case 809 -> "开放水域游泳";
            case 36 -> "登山";
            case 37 -> "越野跑";
            case 19 -> "室内健走";
            default -> "运动";
        };
    }

    static final class Session {
        final int mode;
        final String name;
        final long startMs;
        final long endMs;
        final Integer calories;
        final Integer distance;
        final Integer reps;
        final Integer heart;
        /** Moving time in seconds. Null when the summary did not carry one. */
        final Integer duration;
        /** Fastest pace in seconds per kilometre. Null when it is missing or implausible. */
        final Integer bestPace;

        Session(int mode, String name, long startMs, long endMs, Integer calories, Integer distance,
                Integer reps, Integer heart, Integer duration, Integer bestPace) {
            this.mode = mode;
            this.name = name;
            this.startMs = startMs;
            this.endMs = endMs;
            this.calories = calories;
            this.distance = distance;
            this.reps = reps;
            this.heart = heart;
            this.duration = duration;
            this.bestPace = bestPace;
        }
    }

    private static final class Mapped {
        final int mode;
        final String name;
        final boolean repsAreJumps;

        Mapped(int mode, String name, boolean repsAreJumps) {
            this.mode = mode;
            this.name = name;
            this.repsAreJumps = repsAreJumps;
        }
    }
}
