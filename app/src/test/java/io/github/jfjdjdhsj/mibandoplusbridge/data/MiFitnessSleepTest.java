// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class MiFitnessSleepTest {
    @Test public void stagedSessionKeepsTheBandStageNumbers() {
        String json = "{\"bedtime\":1793888940,\"wake_up_time\":1793892540,"
                + "\"timezone\":32,\"is_uncomplete\":false,\"items\":["
                + "{\"start_time\":1793888940,\"end_time\":1793890620,\"state\":3},"
                + "{\"start_time\":1793890620,\"end_time\":1793891700,\"state\":4},"
                + "{\"start_time\":1793891700,\"end_time\":1793892540,\"state\":2}]}";
        MiFitnessSleep.Session session = MiFitnessSleep.parse(json, "Asia/Shanghai");
        assertEquals(1793888940000L, session.startMs());
        assertEquals(1793892540000L, session.endMs());
        assertEquals("Asia/Shanghai", session.timezone());
        assertTrue(session.complete());
        List<MiFitnessSleep.Stage> stages = session.stages();
        assertEquals(3, stages.size());
        assertEquals(3, stages.get(0).state());
        assertEquals(4, stages.get(1).state());
        assertEquals(2, stages.get(2).state());
        assertEquals(1793890620000L, stages.get(0).endMs());
    }

    @Test public void napWithoutStagesStaysAnEmptyStageList() {
        String json = "{\"bedtime\":1793854680,\"wake_up_time\":1793857800,"
                + "\"timezone\":32,\"is_uncomplete\":false}";
        MiFitnessSleep.Session session = MiFitnessSleep.parse(json, "");
        assertTrue(session.stages().isEmpty());
        assertEquals(1793854680000L, session.startMs());
        assertEquals("GMT+08:00", session.timezone());
    }

    @Test public void unknownZoneFallsBackToTheQuarterHourOffset() {
        MiFitnessSleep.Session session = MiFitnessSleep.parse(
                "{\"bedtime\":1000,\"wake_up_time\":2000,\"timezone\":-20}", "Not/AZone");
        assertEquals("GMT-05:00", session.timezone());
    }

    @Test public void stagesAreClippedToTheSessionAndUnknownStatesDropped() {
        String json = "{\"bedtime\":1000,\"wake_up_time\":2000,\"items\":["
                + "{\"start_time\":900,\"end_time\":1100,\"state\":3},"
                + "{\"start_time\":1200,\"end_time\":1300,\"state\":9},"
                + "{\"start_time\":1900,\"end_time\":2500,\"state\":2}]}";
        MiFitnessSleep.Session session = MiFitnessSleep.parse(json, "Asia/Shanghai");
        assertEquals(2, session.stages().size());
        assertEquals(1000000L, session.stages().get(0).startMs());
        assertEquals(2000000L, session.stages().get(1).endMs());
    }

    @Test public void sleepVitalsComeFromTheSessionSummary() {
        String json = "{\"bedtime\":1793888940,\"wake_up_time\":1793916540,"
                + "\"avg_breath\":14,\"min_hr\":59,\"max_hr\":75,\"avg_hr\":66}";
        MiFitnessSleep.Session session = MiFitnessSleep.parse(json, "Asia/Shanghai");
        assertEquals(Integer.valueOf(14), session.avgBreath());
        assertEquals(Integer.valueOf(59), session.minHr());
        assertEquals(Integer.valueOf(75), session.maxHr());
        assertEquals(Integer.valueOf(66), session.avgHr());
    }

    @Test public void impossibleSleepVitalsAreDropped() {
        MiFitnessSleep.Session session = MiFitnessSleep.parse(
                "{\"bedtime\":1000,\"wake_up_time\":2000,\"avg_breath\":0,"
                        + "\"min_hr\":10,\"max_hr\":400,\"avg_hr\":70}", "Asia/Shanghai");
        assertNull(session.avgBreath());
        assertNull(session.minHr());
        assertNull(session.maxHr());
        assertEquals(Integer.valueOf(70), session.avgHr());
    }

    @Test public void brokenJsonAndInvertedBoundsAreRejected() {
        assertNull(MiFitnessSleep.parse("{", "Asia/Shanghai"));
        assertNull(MiFitnessSleep.parse("{\"bedtime\":2000,\"wake_up_time\":1000}", "Asia/Shanghai"));
        assertNull(MiFitnessSleep.parse("{\"bedtime\":0,\"wake_up_time\":1000}", "Asia/Shanghai"));
        assertFalse(MiFitnessSleep.parse("{\"bedtime\":1000,\"wake_up_time\":2000,\"is_uncomplete\":true}",
                "Asia/Shanghai").complete());
    }
}
