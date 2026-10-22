// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The bridge's local protocol, spoken over one end of a socket pair the client
 * app creates and hands over through Binder ([TRANSACTION_CONNECT]). Documented
 * in PROTOCOL.md; this file is its reference implementation on the bridge's side.
 *
 * Every message is one frame: `[type: u8][length: u32 big-endian][payload]`.
 */
object Protocol {
    /** Binder transaction carrying the client's socket end; the reply is [VERSION]. */
    const val TRANSACTION_CONNECT = 1
    const val VERSION = 1

    // App → module
    /** A JSON request `{"id": n, "op": "...", ...}`, answered by one [RESPONSE] with the same id. */
    const val REQUEST = 0x01
    /** The pad: seven big-endian i32 — buttons, l2, r2, lx, ly, rx, ry. */
    const val PAD = 0x02
    /** Ask the server for a keyframe. Empty payload. */
    const val KEYFRAME = 0x03

    // Module → app
    /** `{"id": n, "ok": true, ...}` or `{"id": n, "ok": false, "error": "code"}`. */
    const val RESPONSE = 0x81
    /** The session's state, sent on every change: `{"state", "kind", "step", "detail", "error"}`. */
    const val STATUS = 0x82
    /** One video sample as chiaki delivers it: Annex-B, a codec header or one access unit. */
    const val VIDEO = 0x83
    /** Two big-endian i32: channels, sample rate. Precedes the [AUDIO] it describes. */
    const val AUDIO_FORMAT = 0x84
    /** Interleaved 16-bit little-endian PCM. */
    const val AUDIO = 0x85

    /** A frame bigger than this is a broken stream, not a video frame. */
    const val MAX_FRAME = 8 * 1024 * 1024

    class Frame(val type: Int, val payload: ByteArray)

    fun read(input: DataInputStream): Frame {
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME) throw IOException("Frame of $length bytes")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, payload)
    }

    /** Callers serialise writes on the stream themselves. */
    fun write(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
