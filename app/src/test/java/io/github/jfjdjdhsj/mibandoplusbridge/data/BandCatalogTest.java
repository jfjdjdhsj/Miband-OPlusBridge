// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BandCatalogTest {
    @Test public void officialModelIdsMapToBandFamilyNames() {
        assertEquals("小米手环8", BandCatalog.displayName("miwear.watch.m66", ""));
        assertEquals("小米手环8 NFC", BandCatalog.displayName("miwear.watch.m66nfc", "ignored"));
        assertEquals("小米手环8 Pro", BandCatalog.displayName("lchz.watch.m67", "Xiaomi Smart Band 8 Pro ABCD"));
        assertEquals("小米手环8 活力版", BandCatalog.displayName("mijia.watch.m69bgl", null));
        assertEquals("小米手环9", BandCatalog.displayName("miwear.watch.n66cn", "Xiaomi Smart Band 9 F123"));
        assertEquals("小米手环9 NFC", BandCatalog.displayName("miwear.watch.n66nfc", ""));
        assertEquals("小米手环9 Pro", BandCatalog.displayName("miwear.watch.n67cn", ""));
        assertEquals("Redmi手环3", BandCatalog.displayName("miwear.watch.n69gl", ""));
        assertEquals("Redmi手环3", BandCatalog.displayName("miwear.watch.n69cn", ""));
        assertEquals("小米手环11 陶瓷版", BandCatalog.displayName("miwear.watch.q66tc", ""));
        assertEquals("小米手环8 Pro 原神定制版", BandCatalog.displayName("lchz.watch.m67ys", ""));
        assertEquals("小米手表 S4", BandCatalog.displayName("mijia.watch.o62", ""));
        assertEquals("REDMI Watch 5", BandCatalog.displayName("miwear.watch.o65", ""));
        assertEquals("小米手环7 Pro", BandCatalog.displayName("hqbd3.watch.l67", ""));
        assertEquals("WRISTBAND", BandCatalog.spaceType("miwear.watch.q66cn"));
        assertEquals("default_wristband", BandCatalog.spaceIcon("miwear.watch.q66cn"));
        assertEquals("WATCH", BandCatalog.spaceType("mijia.watch.o62"));
        assertEquals("default_watch", BandCatalog.spaceIcon("miwear.watch.p65"));
        assertFalse(BandCatalog.watchForm("lchz.watch.m67"));
        assertTrue(BandCatalog.watchForm("hqbd3.watch.l67"));
        assertEquals("Band", BandCatalog.healthCategory("miwear.watch.n66gl"));
        assertEquals("Band", BandCatalog.healthCategory("mijia.watch.band01"));
        assertEquals("Watch", BandCatalog.healthCategory("miwear.watch.q65acn"));
        assertEquals("Watch", BandCatalog.healthCategory("mijia.watch.n62lte"));
        assertEquals("Band", BandCatalog.healthCategory("future.watch.z99"));
        assertEquals("小米手环10", BandCatalog.displayName("miwear.watch.o66cn", ""));
        assertEquals("小米手环10 NFC", BandCatalog.displayName("miwear.watch.o66gln", ""));
        assertEquals("小米手环10 Pro", BandCatalog.displayName("miwear.watch.p67cn", "Xiaomi Smart Band 10 Pro 9C00"));
        assertEquals("小米手环11", BandCatalog.displayName("miwear.watch.q66cn", "Xiaomi Smart Band 11 F488"));
    }

    @Test public void bluetoothNamesIdentifySupportedBandsWithoutModel() {
        assertEquals("小米手环8", BandCatalog.displayName(null, "Xiaomi Smart Band 8 ABCD"));
        assertEquals("小米手环8 Pro", BandCatalog.displayName("", "Xiaomi Smart Band 8 Pro 12AF"));
        assertEquals("小米手环8 活力版", BandCatalog.displayName(null, "Xiaomi Band 8 Active 00A1"));
        assertEquals("小米手环9", BandCatalog.displayName(null, "Xiaomi Smart Band 9 F123"));
        assertEquals("小米手环10 Pro", BandCatalog.displayName(null, "Xiaomi Smart Band 10 Pro 9C00"));
        assertEquals("小米手环11", BandCatalog.displayName(null, "小米手环11"));
        assertTrue(BandCatalog.looksLikeBand("Xiaomi Smart Band 8 ABCD"));
        assertTrue(BandCatalog.looksLikeBand("Redmi Smart Band 3"));
        assertEquals("红米手环 Pro", BandCatalog.displayName(null, "Redmi Band Pro"));
        assertEquals("红米手表5", BandCatalog.displayName(null, "REDMI Watch 5"));
        assertEquals("红米手表5", BandCatalog.displayName(null, "Redmi Watch 5 eSIM"));
        assertTrue(BandCatalog.looksLikeBand("REDMI Watch 5"));
        assertTrue(BandCatalog.looksLikeBand("Xiaomi Smart Band 10 1111"));
        assertFalse(BandCatalog.looksLikeBand("WH-1000XM5"));
        assertFalse(BandCatalog.looksLikeBand("OPPO Watch 2"));
        assertFalse(BandCatalog.looksLikeBand("miwear.watch.m66"));
    }

    @Test public void unknownSppModelsKeepRawNameAndAreNotRejected() {
        assertEquals("some custom name", BandCatalog.displayName("future.watch.z99", "some custom name"));
        assertEquals("future.watch.z99", BandCatalog.displayName("future.watch.z99", ""));
        assertEquals("小米手环", BandCatalog.displayName(null, null));
        assertEquals("小米手环", BandCatalog.displayName("", "   "));
    }
}
