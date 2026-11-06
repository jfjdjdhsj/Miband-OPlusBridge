// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class NotificationIconUploadTest {
    @Test public void rgb565PacksFiveSixFive() {
        int opaqueRed = 0xffff0000;
        byte[] encoded = NotificationIconPixels.encode(new int[] {opaqueRed}, 1, 1,
                NotificationIconPixels.RGB_565_LE);
        assertArrayEquals(new byte[] {0x00, (byte) 0xf8}, encoded);
    }

    @Test public void uploadEnvelopeMatchesTypeMd5SizeAndCrc() {
        byte[] pixels = NotificationIconPixels.encode(new int[] {0xff112233}, 1, 1,
                NotificationIconPixels.ARGB_8888_LE);
        XiaomiProto.Command request = NotificationIconUpload.request(pixels);
        assertEquals(22, request.getType());
        assertEquals(50, request.getDataUpload().getDataUploadRequest().getType());
        assertEquals(pixels.length, request.getDataUpload().getDataUploadRequest().getSize());
        assertArrayEquals(NotificationIconUpload.md5(pixels),
                request.getDataUpload().getDataUploadRequest().getMd5Sum().toByteArray());

        byte[] envelope = NotificationIconUpload.envelope(pixels);
        assertEquals(0, envelope[0]);
        assertEquals(50, envelope[1] & 0xff);
        CRC32 crc = new CRC32();
        crc.update(envelope, 0, envelope.length - 4);
        int stored = ByteBuffer.wrap(envelope, envelope.length - 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertEquals((int) crc.getValue(), stored);
    }

    @Test public void chunksCarryPartNumbers() {
        byte[] envelope = new byte[10];
        Arrays.fill(envelope, (byte) 7);
        var parts = NotificationIconUpload.chunks(envelope, 8);
        assertEquals(3, parts.size());
        assertEquals(3, parts.get(0)[0] & 0xff);
        assertEquals(1, parts.get(0)[2] & 0xff);
        assertEquals(4, parts.get(0).length - 4);
        assertEquals(3, parts.get(2)[2] & 0xff);
        assertEquals(2, parts.get(2).length - 4);
    }

    @Test public void rejectedAckDoesNotStart() {
        XiaomiProto.Command ack = XiaomiProto.Command.newBuilder().setType(22).setSubtype(0)
                .setDataUpload(XiaomiProto.DataUpload.newBuilder().setDataUploadAck(
                        XiaomiProto.DataUploadAck.newBuilder().setUnknown2(1).setChunkSize(2048)))
                .build();
        assertThrows(IllegalArgumentException.class, () -> NotificationIconUpload.acceptedChunk(ack, 2000));
    }
}
