package com.mu.qrcodestream.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FountainRoundTripTest {

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] decodeAll(FountainEncoder encoder, double dropRate, long seed) {
        FountainDecoder decoder = null;
        Random random = new Random(seed);
        for (int i = 0; i < encoder.frameCount(); i++) {
            if (random.nextDouble() < dropRate) {
                continue;
            }
            Frame frame = Protocol.parseFrame(encoder.frameAt(i));
            if (decoder == null) {
                decoder = new FountainDecoder(frame.getChunkLen(), frame.getTotal());
            }
            decoder.add(frame);
        }
        assertNotNull(decoder, "no frames accepted");
        assertTrue(decoder.isComplete(), "decoder did not complete");
        return decoder.payload();
    }

    @Test
    void largeFileNoLossRoundTrip() {
        byte[] file = randomBytes(90_000, 11);
        FountainEncoder encoder =
                FountainEncoder.forFile(file, Protocol.DEFAULT_CHUNK_LEN, Protocol.DEFAULT_REDUNDANCY);
        byte[] payload = decodeAll(encoder, 0.0, 1);

        Envelope envelope = Protocol.parseEnvelope(new String(payload));
        assertArrayEquals(file, envelope.getData());
        assertTrue(envelope.verify());
    }

    @Test
    void twentyPercentLossRoundTrip() {
        byte[] file = randomBytes(200_000, 21);
        FountainEncoder encoder =
                FountainEncoder.forFile(file, Protocol.DEFAULT_CHUNK_LEN, Protocol.DEFAULT_REDUNDANCY);
        byte[] payload = decodeAll(encoder, 0.2, 2);

        Envelope envelope = Protocol.parseEnvelope(new String(payload));
        assertArrayEquals(file, envelope.getData());
        assertTrue(envelope.verify());
    }

    @Test
    void tinyPayloadSingleBlock() {
        byte[] file = "abc".getBytes();
        FountainEncoder encoder =
                FountainEncoder.forFile(file, Protocol.DEFAULT_CHUNK_LEN, Protocol.DEFAULT_REDUNDANCY);
        byte[] payload = decodeAll(encoder, 0.0, 3);

        Envelope envelope = Protocol.parseEnvelope(new String(payload));
        assertArrayEquals(file, envelope.getData());
        assertTrue(envelope.verify());
    }

    @Test
    void duplicateFramesAreIgnored() {
        byte[] file = randomBytes(30_000, 31);
        FountainEncoder encoder =
                FountainEncoder.forFile(file, Protocol.DEFAULT_CHUNK_LEN, Protocol.DEFAULT_REDUNDANCY);
        Frame first = Protocol.parseFrame(encoder.frameAt(0));
        FountainDecoder decoder = new FountainDecoder(first.getChunkLen(), first.getTotal());

        assertTrue(decoder.add(first));
        assertEqualsReceived(decoder, 1);
    }

    private static void assertEqualsReceived(FountainDecoder decoder, int expected) {
        assertTrue(decoder.receivedCount() == expected, "received count should ignore duplicates");
    }

    @Test
    void firstSourceBlocksAreSystematic() {
        byte[] file = randomBytes(50_000, 7);
        FountainEncoder encoder =
                FountainEncoder.forFile(file, Protocol.DEFAULT_CHUNK_LEN, Protocol.DEFAULT_REDUNDANCY);
        Frame first = Protocol.parseFrame(encoder.frameAt(0));
        FountainDecoder decoder = new FountainDecoder(first.getChunkLen(), first.getTotal());

        int sourceBlocks = encoder.sourceBlockCount();
        for (int i = 0; i < sourceBlocks; i++) {
            decoder.add(Protocol.parseFrame(encoder.frameAt(i)));
        }

        assertTrue(decoder.isComplete(), "the first K frames should solve every block directly");
        Envelope envelope = Protocol.parseEnvelope(new String(decoder.payload()));
        assertArrayEquals(file, envelope.getData());
        assertTrue(envelope.verify());
    }

    @Test
    void wrongParametersRejected() {
        FountainEncoder encoder =
                FountainEncoder.forFile(randomBytes(5000, 5), Protocol.DEFAULT_CHUNK_LEN, 2.0);
        FountainDecoder decoder = new FountainDecoder(Protocol.DEFAULT_CHUNK_LEN, 1);
        assertTrue(!decoder.add(Protocol.parseFrame(encoder.frameAt(0))));
        assertNull(decoder.payload());
    }
}
