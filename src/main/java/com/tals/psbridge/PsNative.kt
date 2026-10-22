// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

/**
 * The native side of the PS5 mode: chiaki (PlayStation Remote Play), reached
 * through `src/main/cpp/ps-jni.c`. Every call blocks; none of them may run on
 * the main thread.
 *
 * Handles are opaque pointers (0 = none). A hole punch belongs to the caller
 * until [sessionCreate] takes it over, then to the session.
 */
object PsNative {

    /** Callbacks from chiaki's own threads. Names and signatures are looked up by the bridge. */
    interface Listener {
        fun onConnected()
        fun onQuit(reason: Int, reasonString: String?)
        fun onRumble(left: Int, right: Int)
        /** One complete access unit (Annex-B), or the codec header on its own before the first one. */
        fun onVideo(data: ByteArray, framesLost: Int)
        fun onAudioFormat(channels: Int, rate: Int)
        /** Interleaved 16-bit little-endian PCM in the last announced format. */
        fun onAudio(pcm: ByteArray)
    }

    const val CONSOLE_PS4 = 0
    const val CONSOLE_PS5 = 1

    const val PORT_CTRL = 0
    const val PORT_DATA = 1

    const val CODEC_H264 = 0
    const val CODEC_H265 = 1

    /** chiaki's ChiakiQuitReason values the engine tells apart. */
    const val QUIT_STOPPED = 1
    const val QUIT_RP_IN_USE = 4
    const val QUIT_REMOTE_SHUTDOWN = 12

    private val loaded: Boolean = runCatching { System.loadLibrary("psbridge") }.isSuccess

    /** False on a build without the native library; the PS5 card then says so instead of crashing. */
    val available: Boolean get() = loaded

    @JvmStatic external fun setCaBundle(path: String)
    @JvmStatic external fun errorString(code: Int): String
    @JvmStatic external fun quitReasonString(reason: Int): String

    /** Flattened, four strings per console: name, uid (hex), "1" if Remote Play is on, type. */
    @JvmStatic external fun listDevices(token: String, consoleType: Int): Array<String>?

    @JvmStatic external fun holepunchInit(token: String): Long
    @JvmStatic external fun holepunchUpnp(hp: Long): Int
    @JvmStatic external fun holepunchCreate(hp: Long): Int
    @JvmStatic external fun holepunchOffer(hp: Long): Int
    @JvmStatic external fun holepunchStart(hp: Long, consoleUid: ByteArray, consoleType: Int): Int
    @JvmStatic external fun holepunchPunch(hp: Long, portType: Int): Int
    @JvmStatic external fun holepunchLastError(hp: Long): String?
    @JvmStatic external fun holepunchCancel(hp: Long)
    @JvmStatic external fun holepunchFini(hp: Long)

    @JvmStatic external fun sessionCreate(
        hp: Long, accountId: ByteArray, ps5: Boolean,
        width: Int, height: Int, fps: Int, bitrateKbps: Int, codec: Int,
        listener: Listener
    ): Long
    /** A PS Plus cloud session on the machine Sony allocated (see ps/cloud/CloudStreamingBackend). */
    @JvmStatic external fun sessionCreateCloud(
        host: String, port: Int, launchSpec: String, handshakeKey: String, sessionId: String,
        wrapperType: Int, mtuIn: Int, mtuOut: Int, rttUs: Long, pscloud: Boolean,
        ps5: Boolean, width: Int, height: Int, fps: Int, bitrateKbps: Int, codec: Int,
        listener: Listener
    ): Long
    @JvmStatic external fun sessionStart(session: Long): Int
    @JvmStatic external fun sessionStop(session: Long): Int
    @JvmStatic external fun sessionJoin(session: Long): Int
    @JvmStatic external fun sessionFree(session: Long)
    @JvmStatic external fun sessionSetController(
        session: Long, buttons: Int, l2: Int, r2: Int, lx: Int, ly: Int, rx: Int, ry: Int
    )
    @JvmStatic external fun sessionRequestIdr(session: Long): Int
    @JvmStatic external fun sessionGotoBed(session: Long): Int

    /** One PS Plus datacenter measured: {rtt in µs (-1 on failure), MTU in, MTU out}. */
    @JvmStatic external fun pingDatacenter(ip: String, port: Int, sessionKey: String, pscloud: Boolean): LongArray

    /** chiaki's CHIAKI_ERR_SUCCESS. */
    const val OK = 0
}
