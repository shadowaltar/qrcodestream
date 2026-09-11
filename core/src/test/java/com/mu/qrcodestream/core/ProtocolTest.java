package com.mu.qrcodestream.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ProtocolTest {

    @Test
    void frameRoundTrip() {
        byte[] data = new byte[Protocol.DEFAULT_CHUNK_LEN];
        new Random(1).nextBytes(data);

        String text = Protocol.buildFrame(42, Protocol.DEFAULT_CHUNK_LEN, 12345, data);
        Frame frame = Protocol.parseFrame(text);

        assertNotNull(frame);
        assertEquals(42, frame.getBlockCode());
        assertEquals(Protocol.DEFAULT_CHUNK_LEN, frame.getChunkLen());
        assertEquals(12345, frame.getTotal());
        assertArrayEquals(data, frame.getData());
    }

    @Test
    void invalidFrameReturnsNull() {
        assertNull(Protocol.parseFrame("not a frame"));
        assertNull(Protocol.parseFrame("1/2|xx"));
        assertNull(Protocol.parseFrame(null));
    }

    @Test
    void envelopeRoundTripAndVerify() {
        byte[] file = "hello optical transfer".getBytes();
        String envelope = Protocol.buildEnvelope(file);
        assertTrue(envelope.startsWith(Protocol.MAGIC + "|"));

        Envelope parsed = Protocol.parseEnvelope(envelope);
        assertEquals(file.length, parsed.getOriginalLength());
        assertArrayEquals(file, parsed.getData());
        assertTrue(parsed.verify());
    }

    @Test
    void envelopeVerifyDetectsCorruption() {
        byte[] file = new byte[1000];
        new Random(7).nextBytes(file);
        Envelope parsed = Protocol.parseEnvelope(Protocol.buildEnvelope(file));

        Envelope corrupted = new Envelope(parsed.getOriginalLength(), parsed.getSha256(),
                parsed.getData().clone());
        corrupted.getData()[0] ^= 0x01;
        assertFalse(corrupted.verify());
    }
}
