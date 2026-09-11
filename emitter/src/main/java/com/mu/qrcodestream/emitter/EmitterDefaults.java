package com.mu.qrcodestream.emitter;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mu.qrcodestream.core.Protocol;

/** Fixed emitter defaults (the UI exposes QR version and frame rate; the rest are constants). */
final class EmitterDefaults {

    private EmitterDefaults() {}

    static final ErrorCorrectionLevel EC = ErrorCorrectionLevel.L;
    static final int SCALE = 8;
    static final double REDUNDANCY = Protocol.DEFAULT_REDUNDANCY;
    static final int HEADLESS_CHUNK_LEN = Protocol.DEFAULT_CHUNK_LEN;
    static final int FPS = Protocol.DEFAULT_FPS;
}
