// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.protocol;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/**
 * Type 22 upload used after the band accepts a notification icon.
 * The envelope is type, MD5, size, pixels, then CRC-32, split into numbered parts.
 */
public final class NotificationIconUpload {
    public static final int COMMAND_TYPE = 22;
    public static final int ICON_TYPE = 50;
    public static final int DEFAULT_CHUNK = 2048;

    private NotificationIconUpload() {}

    public static XiaomiProto.Command request(byte[] pixels) {
        if (pixels == null || pixels.length == 0) throw new IllegalArgumentException("ICON_PAYLOAD");
        return XiaomiProto.Command.newBuilder().setType(COMMAND_TYPE).setSubtype(0)
                .setDataUpload(XiaomiProto.DataUpload.newBuilder().setDataUploadRequest(
                        XiaomiProto.DataUploadRequest.newBuilder()
                                .setType(ICON_TYPE)
                                .setMd5Sum(ByteString.copyFrom(md5(pixels)))
                                .setSize(pixels.length)))
                .build();
    }

    /** Bytes carried inside each data-channel part, before the part header. */
    public static byte[] envelope(byte[] pixels) {
        byte[] digest = md5(pixels);
        ByteBuffer body = ByteBuffer.allocate(2 + digest.length + 4 + pixels.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.put((byte) 0);
        body.put((byte) ICON_TYPE);
        body.put(digest);
        body.putInt(pixels.length);
        body.put(pixels);
        byte[] raw = body.array();
        CRC32 crc = new CRC32();
        crc.update(raw);
        ByteBuffer framed = ByteBuffer.allocate(raw.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        framed.put(raw);
        framed.putInt((int) crc.getValue());
        return framed.array();
    }

    /**
     * The band's start ack. Its {@code dataUpload} body is optional in practice: some firmware
     * answers with an empty command and expects the default 2048-byte chunks, so only an explicit
     * error field aborts the upload. A non-zero resume position is ignored on purpose: the watch
     * reports one for icons it has never received (49118 bytes for a 123904-byte icon on the
     * Redmi Watch 5), so honouring it would skip the head of the payload.
     */
    public static int acceptedChunk(XiaomiProto.Command ack, int wireLimit) {
        XiaomiProto.DataUploadAck reply = ack != null && ack.hasDataUpload()
                && ack.getDataUpload().hasDataUploadAck()
                ? ack.getDataUpload().getDataUploadAck() : null;
        if (reply != null && reply.getUnknown2() != 0) {
            throw new IllegalArgumentException("ICON_UPLOAD_REJECTED");
        }
        int size = reply != null && reply.hasChunkSize() && reply.getChunkSize() > 0
                ? reply.getChunkSize() : DEFAULT_CHUNK;
        if (wireLimit > 0) size = Math.min(size, wireLimit);
        if (size < 8) throw new IllegalArgumentException("ICON_CHUNK");
        return size;
    }

    /** One line describing the ack, so a failed upload can be diagnosed from the session log. */
    public static String describeAck(XiaomiProto.Command ack) {
        if (ack == null) return "ack=null";
        XiaomiProto.DataUploadAck reply = ack.hasDataUpload()
                && ack.getDataUpload().hasDataUploadAck()
                ? ack.getDataUpload().getDataUploadAck() : null;
        StringBuilder text = new StringBuilder("status=").append(ack.getStatus())
                .append(" body=").append(reply != null);
        if (reply != null) {
            text.append(" unknown2=").append(reply.getUnknown2())
                    .append(" resume=").append(reply.getResumePosition())
                    .append(" chunk=").append(reply.hasChunkSize() ? reply.getChunkSize() : -1);
        }
        byte[] raw = ack.toByteArray();
        text.append(" hex=");
        for (int i = 0; i < Math.min(raw.length, 48); i++) {
            text.append(Character.forDigit((raw[i] >> 4) & 0xf, 16))
                    .append(Character.forDigit(raw[i] & 0xf, 16));
        }
        return text.toString();
    }

    public static List<byte[]> chunks(byte[] envelope, int chunkSize) {
        if (envelope == null || envelope.length == 0 || chunkSize < 8) {
            throw new IllegalArgumentException("ICON_CHUNK");
        }
        int partSize = chunkSize - 4;
        int total = (envelope.length + partSize - 1) / partSize;
        if (total > 0xffff) throw new IllegalArgumentException("ICON_CHUNK");
        List<byte[]> parts = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int start = index * partSize;
            int length = Math.min(partSize, envelope.length - start);
            byte[] part = new byte[4 + length];
            int number = index + 1;
            part[0] = (byte) total;
            part[1] = (byte) (total >> 8);
            part[2] = (byte) number;
            part[3] = (byte) (number >> 8);
            System.arraycopy(envelope, start, part, 4, length);
            parts.add(part);
        }
        return parts;
    }

    static byte[] md5(byte[] pixels) {
        try {
            return MessageDigest.getInstance("MD5").digest(pixels);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("ICON_MD5");
        }
    }
}
