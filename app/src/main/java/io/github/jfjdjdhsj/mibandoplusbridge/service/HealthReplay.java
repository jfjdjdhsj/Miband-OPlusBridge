// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.service;

import android.content.Context;
import android.database.ContentObserver;
import android.os.UserManager;
import io.github.jfjdjdhsj.mibandoplusbridge.data.BindingStore;
import io.github.jfjdjdhsj.mibandoplusbridge.data.BandStateRepository;
import io.github.jfjdjdhsj.mibandoplusbridge.data.HealthRecordStore;
import io.github.jfjdjdhsj.mibandoplusbridge.data.MiFitnessDatabase;
import io.github.jfjdjdhsj.mibandoplusbridge.data.MiFitnessSleep;
import io.github.jfjdjdhsj.mibandoplusbridge.data.RawFitnessFileStore;
import io.github.jfjdjdhsj.mibandoplusbridge.data.SleepStageAlign;
import io.github.jfjdjdhsj.mibandoplusbridge.data.WalkingHeartRate;
import io.github.jfjdjdhsj.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.jfjdjdhsj.mibandoplusbridge.protocol.BandHistoryParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Replays durable device/account-owned files without occupying the socket or command coordinator. */
final class HealthReplay implements AutoCloseable {
    private static final int WALKING_BACKFILL_DAYS = 30;
    private static final long DAY_HALF_MS = 43_200_000L;
    private static final long MI_SLEEP_INTERVAL_MS = 30L * 60_000L;
    private static final long MI_SLEEP_WINDOW_MS = 30L * 86_400_000L;
    private static volatile boolean walkingBackfilled;
    private final Context context;
    private final HealthRecordStore records;
    private final RawFitnessFileStore raw;
    private final Consumer<String> status;
    private final Runnable capacityChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "OplusBandHealthReplay"));
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closed;
    private boolean legacyIndexed;
    private boolean sleepStagesAligned;
    private final ContentObserver observer = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) { request(); }
    };

    HealthReplay(Context context, Consumer<String> status, Runnable capacityChanged) {
        this.context = context.getApplicationContext();
        this.records = new HealthRecordStore(this.context);
        this.raw = new RawFitnessFileStore(this.context);
        this.status = status;
        this.capacityChanged = capacityChanged;
        this.context.getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, observer);
    }

    boolean hasCapacity() {
        if (closed) return false;
        try { return records.hasCapacity(); }
        catch (RuntimeException unavailable) {
            status.accept("HEALTH_STORAGE_UNAVAILABLE");
            throw unavailable;
        }
    }

    void request() {
        if (closed) return;
        requested.set(true);
        if (!running.compareAndSet(false, true)) return;
        try { worker.execute(this::drain); }
        catch (RejectedExecutionException stopped) { running.set(false); }
    }

    private void drain() {
        try {
            while (!closed && requested.getAndSet(false)) replay();
        } catch (Exception failure) {
            String code = failure.getMessage();
            status.accept("HEALTH_OUTBOX_FULL".equals(code) ? "HEALTH_OUTBOX_FULL"
                    : "ACCOUNT_CONFIRMATION_REQUIRED".equals(code) ? "ACCOUNT_CONFIRMATION_REQUIRED"
                    : "HEALTH_REPLAY_PAUSED");
        } finally {
            running.set(false);
            if (!closed) {
                capacityChanged.run();
                if (requested.get()) request();
            }
        }
    }

    private void replay() throws Exception {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
        if (!legacyIndexed) {
            raw.migrateLegacyFiles();
            legacyIndexed = true;
        }
        if (records.confirmedAccountHash() == null) {
            status.accept("ACCOUNT_CONFIRMATION_REQUIRED");
            return;
        }
        int reparsed = raw.promoteRejectedSleep();
        int sportReparsed = raw.promoteRejectedSport();
        if (sportReparsed > 0) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context, "history sport reparsed " + sportReparsed);
        }
        if (reparsed > 0) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context, "history sleep reparsed " + reparsed);
        }
        int breathReparsed = raw.refreshSleepBreathMetrics();
        if (breathReparsed > 0) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context, "history sleep breath enqueued " + breathReparsed);
        }
        int restingExtended = raw.refreshRestingHeart();
        if (restingExtended > 0) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context, "history resting heart extended " + restingExtended);
        }
        var binding = new BindingStore(context).readIdentity();
        String identity = binding.did();
        if (identity.isBlank()) identity = binding.address().replace(":", "");
        String deviceId = binding.deviceId();
        Set<java.time.LocalDate> measuredDays = new HashSet<>();
        while (!closed) {
            var files = records.replayFiles(4);
            if (files.isEmpty()) break;
            boolean added = false;
            boolean sawSleep = false;
            try {
                for (var file : files) {
                    if (closed) return;
                    if (!deviceId.equals(file.deviceId())) throw new IllegalStateException("HEALTH_COLLECTION_IDENTITY_CHANGED");
                    var parsed = new BandHistoryParser(file.firmware(), file.deviceId(), identity)
                            .parseFile(raw.readFile(file.fileHash()));
                    int indexed = file.recordCount();
                    // A daily report gained its resting heart-rate row. Only a trailing daily
                    // heart value may grow an archived file; anything else is a parser change.
                    if (parsed.measurements.size() > indexed
                            && BandHistoryParser.onlyDailyHeartFrom(parsed.measurements, indexed)
                            && records.extendParsedRecordCount(file.fileHash(), parsed.measurements.size())) {
                        indexed = parsed.measurements.size();
                    }
                    if (!"PARSED".equals(parsed.parseStatus) || parsed.measurements.size() != indexed) {
                        throw new IllegalStateException("HEALTH_ARCHIVE_PARSE_CHANGED");
                    }
                    for (int index = file.nextRecordIndex(); index < parsed.measurements.size(); index++) {
                        if (closed) return;
                        var measurement = parsed.measurements.get(index);
                        var result = records.enqueueArchivedMeasurement(file.fileHash(), index, measurement);
                        added |= result.added();
                        if (result.added()) measuredDays.add(localDay(measurement));
                        if ("sleep_interval".equals(measurement.kind) || "sleep_stage".equals(measurement.kind)) {
                            sawSleep = true;
                        }
                    }
                }
            } finally {
                if (added) {
                    context.getContentResolver().notifyChange(HealthQueueProvider.URI, null);
                    context.getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
                }
            }
            if (sawSleep) sleepStagesAligned = false;
        }
        // Before the sleep pass: a night that cannot be aligned must not hold the heart values back.
        try {
            deriveWalkingHeart(deviceId, identity, measuredDays);
        } catch (Exception failed) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                    "history walking deferred " + failed.getClass().getSimpleName());
        }
        importMiFitnessSleep(deviceId, identity);
        alignStoredSleep(identity);
        if (!closed) BandStateRepository.refreshStoredSteps(context);
        status.accept("HEALTH_LOCAL_RECORDS_READY");
    }

    /**
     * Mi Fitness keeps a complete night even when the band never uploads its sleep file, which is
     * what the watch does on some firmware. Those sessions enter the same store the band file
     * would, so the existing OPPO writer needs no special case. Re-reading the vendor database
     * spawns a root copy, so the pass is throttled.
     */
    private void importMiFitnessSleep(String deviceId, String identity) {
        if (closed || deviceId == null || deviceId.isBlank()
                || identity == null || identity.isBlank()) return;
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("mi-sleep-at", 0) < MI_SLEEP_INTERVAL_MS) return;
        if (!prefs.edit().putLong("mi-sleep-at", now).commit()) return;
        List<MiFitnessSleep.Session> sessions;
        try {
            sessions = MiFitnessSleep.read(context, now - MI_SLEEP_WINDOW_MS);
        } catch (MiFitnessDatabase.Failure unavailable) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                    "history sleep mifitness " + unavailable.code);
            return;
        } catch (RuntimeException unavailable) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                    "history sleep mifitness " + unavailable.getClass().getSimpleName());
            return;
        }
        if (sessions.isEmpty()) return;
        BandHistoryParser parser;
        try {
            parser = new BandHistoryParser("derived", deviceId, identity);
        } catch (IllegalArgumentException unusable) {
            return;
        }
        int changed = 0;
        for (MiFitnessSleep.Session session : sessions) {
            if (closed) return;
            try {
                boolean added = records.enqueueMeasurement(parser.importedSleepInterval(
                        session.startMs(), session.endMs(), session.timezone(), session.complete())).added();
                for (MiFitnessSleep.Stage stage : session.stages()) {
                    added |= records.enqueueMeasurement(parser.importedSleepStage(
                            stage.startMs(), stage.endMs(), stage.state(), session.timezone())).added();
                }
                added |= importSleepVitals(parser, session);
                if (added) changed++;
            } catch (RuntimeException skipped) {
                io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                        "history sleep mifitness skipped " + skipped.getMessage());
            }
        }
        if (changed > 0) {
            context.getContentResolver().notifyChange(HealthQueueProvider.URI, null);
            context.getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
        }
        io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                "history sleep mifitness sessions=" + sessions.size() + " changed=" + changed);
    }

    private List<io.github.jfjdjdhsj.mibandoplusbridge.data.HealthRecord> stored(
            String deviceId, String kind, long start, long end) throws Exception {
        return records.storedRecords(deviceId, kind, start, end);
    }

    /**
     * The band never uploads breath packets, so the sleep page had no 呼吸率 and no 心率范围.
     * Mi Fitness keeps both in the same session row the stages come from: one average breath rate
     * and the night's own min/max/average heart rate. They enter the store as the band's own kinds,
     * so the existing OPPO writer fills tables 1048, 1049 and 1071 without a special case.
     */
    private boolean importSleepVitals(BandHistoryParser parser, MiFitnessSleep.Session session) {
        boolean added = false;
        long start = session.startMs();
        long end = session.endMs();
        long mid = start + (end - start) / 2;
        mid -= mid % 60_000L;
        Integer breath = session.avgBreath();
        if (breath != null) {
            try {
                added |= records.enqueueMeasurement(parser.importedSleepBreath(
                        mid, breath * 10, session.timezone())).added();
            } catch (RuntimeException unsupported) {
                io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                        "history sleep breath " + unsupported.getMessage());
            }
        }
        Integer min = session.minHr();
        Integer max = session.maxHr();
        Integer avg = session.avgHr();
        if (min != null && max != null && avg != null && min <= avg && avg <= max) {
            long span = end - start;
            long first = start + Math.min(60_000L, span / 4);
            long last = end - Math.min(120_000L, span / 4);
            if (first < mid && mid < last) {
                added |= enqueueSleepHeart(parser, first, min, session.timezone());
                added |= enqueueSleepHeart(parser, mid, avg, session.timezone());
                added |= enqueueSleepHeart(parser, last, max, session.timezone());
            } else {
                added |= enqueueSleepHeart(parser, mid, avg, session.timezone());
            }
        }
        return added;
    }

    private boolean enqueueSleepHeart(BandHistoryParser parser, long atMs, int bpm, String timezone) {
        try {
            return records.enqueueMeasurement(parser.importedSleepHeart(atMs, bpm, timezone)).added();
        } catch (RuntimeException unsupported) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                    "history sleep heart " + unsupported.getMessage());
            return false;
        }
    }

    private static java.time.LocalDate localDay(BandHistoryParser.Measurement measurement) {
        java.time.ZoneId zone = measurement.timezone == null
                ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(measurement.timezone);
        return java.time.Instant.ofEpochMilli(measurement.startMs).atZone(zone).toLocalDate();
    }

    /**
     * Health only shows a walking average when it holds a heart-rate point typed for walking, so
     * each day that gained rows is averaged from its own stored step and heart-rate minutes. The
     * first pass also covers the last month of archived days, which are already fully replayed.
     */
    private void deriveWalkingHeart(String deviceId, String identity, Set<java.time.LocalDate> measuredDays) {
        if (closed) return;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Set<java.time.LocalDate> days = new HashSet<>(measuredDays);
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE);
        boolean backfill = !walkingBackfilled && !prefs.getBoolean("walking-heart-v2", false);
        if (backfill) {
            java.time.LocalDate today = java.time.LocalDate.now(zone);
            for (int back = 0; back < WALKING_BACKFILL_DAYS; back++) days.add(today.minusDays(back));
        }
        if (days.isEmpty()) return;
        BandHistoryParser parser;
        try {
            parser = new BandHistoryParser("derived", deviceId, identity);
        } catch (IllegalArgumentException unusable) {
            io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                    "history walking skipped identity");
            return;
        }
        boolean added = false;
        boolean retry = false;
        for (java.time.LocalDate day : days) {
            if (closed) return;
            long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            try {
                Integer average = WalkingHeartRate.average(
                        stored(deviceId, "steps_interval", start, end),
                        stored(deviceId, "heart_rate", start, end),
                        stored(deviceId, "sport_session", start, end));
                if (average == null) continue;
                // Noon keeps the value inside its own day and out of the night heart-rate window.
                if (records.enqueueMeasurement(
                        parser.dailyHeart("walking", start + DAY_HALF_MS, average, zone.getId())).added()) {
                    added = true;
                }
            } catch (Exception skipped) {
                // An unreadable day is retried by the next pass; the rest of the month still runs.
                if (!retry) {
                    io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                            "history walking failed " + skipped.getClass().getSimpleName()
                                    + " " + skipped.getMessage());
                }
                retry = true;
            }
        }
        io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog.line(context,
                "history walking days=" + days.size() + " derived=" + (added ? 1 : 0)
                        + " retry=" + (retry ? 1 : 0) + " backfill=" + (backfill ? 1 : 0));
        if (backfill && !retry && prefs.edit().putBoolean("walking-heart-v2", true).commit()) {
            walkingBackfilled = true;
        }
        if (added) {
            context.getContentResolver().notifyChange(HealthQueueProvider.URI, null);
            context.getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
        }
    }

    /** The newest file that still has stages owns the interval. Older files leave that interval in place. */
    private void alignStoredSleep(String identity) throws Exception {
        if (sleepStagesAligned || closed) return;
        boolean added = false;
        var files = records.completedFiles();
        Set<String> blocked = new HashSet<>();
        var claimed = new java.util.HashMap<String, List<SleepStageAlign.Interval>>();
        for (int i = files.size() - 1; i >= 0; i--) {
            if (closed) return;
            var file = files.get(i);
            if (blocked.contains(file.deviceId())) continue;
            BandHistoryParser.FileResult parsed;
            try {
                parsed = new BandHistoryParser(file.firmware(), file.deviceId(), identity)
                        .parseFile(raw.readFile(file.fileHash()));
            } catch (RuntimeException unreadable) {
                blocked.add(file.deviceId());
                continue;
            }
            if (!"PARSED".equals(parsed.parseStatus)) {
                blocked.add(file.deviceId());
                continue;
            }
            var intervals = spans(parsed.measurements, file.deviceId(), "sleep_interval");
            var stages = spans(parsed.measurements, file.deviceId(), "sleep_stage");
            var owned = claimed.computeIfAbsent(file.deviceId(), key -> new ArrayList<>());
            var covered = SleepStageAlign.withStages(SleepStageAlign.claim(intervals, owned), stages);
            if (covered.isEmpty()) continue;
            if (records.retainSleepStages(file.deviceId(), parsed.measurements, covered) > 0) added = true;
            owned.addAll(covered);
        }
        sleepStagesAligned = true;
        if (added) {
            context.getContentResolver().notifyChange(HealthQueueProvider.URI, null);
            context.getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
        }
    }

    private static List<SleepStageAlign.Interval> spans(
            List<BandHistoryParser.Measurement> parsed, String deviceId, String kind) {
        List<SleepStageAlign.Interval> found = new ArrayList<>();
        if (parsed == null) return found;
        for (var measurement : parsed) {
            if (measurement != null && deviceId.equals(measurement.deviceId) && kind.equals(measurement.kind)) {
                found.add(new SleepStageAlign.Interval(measurement.startMs, measurement.endMs));
            }
        }
        return found;
    }

    @Override public void close() {
        closed = true;
        context.getContentResolver().unregisterContentObserver(observer);
        worker.execute(records::close);
        worker.shutdown();
    }
}
