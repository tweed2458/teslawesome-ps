// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tals.psbridge.cloud.AuthorizationFailedException
import com.tals.psbridge.cloud.CloudStreamingBackend
import com.tals.psbridge.cloud.DatacenterNotOfferedException
import com.tals.psbridge.cloud.DistantDatacenterBatchException
import com.tals.psbridge.cloud.GameNotStreamableException
import com.tals.psbridge.cloud.PingTimeoutException
import com.tals.psbridge.cloud.PsPlusSubscriptionException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * One PlayStation session at a time: Remote Play of the user's console, reached
 * through PSN hole punching, or a PS Plus cloud game on a machine Sony allocates
 * (ps/cloud, from CloudPad). What the session produces goes to [output] as is;
 * the app decides what to do with it.
 *
 * Connecting blocks on the network for seconds, so it runs on its own thread;
 * every public call returns at once.
 */
class RemoteSession(private val context: Context, private val output: Output) {

    interface Output {
        fun status(status: JSONObject)
        fun video(sample: ByteArray)
        fun audioFormat(channels: Int, rate: Int)
        fun audio(pcm: ByteArray)
    }

    /** What the app asks to play, read from its `start` request. */
    class Start(
        val cloud: Boolean,
        val consoleUid: String?,
        val consolePs5: Boolean,
        val gameStreamId: String?,
        val gameEntitlementId: String?,
        val gameName: String?,
        /** "pscloud" (PS5 cloud games) or "psnow" (the PS4/PS3 catalog, claimed through Kamaji). */
        val gameService: String,
        val gamePlatform: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        /** The codec the client decodes: H.265 when it can, H.264 otherwise. */
        val h265: Boolean,
    ) {
        companion object {
            fun from(req: JSONObject) = Start(
                cloud = req.optString("kind") == "cloud",
                consoleUid = req.optString("console_uid").ifEmpty { null },
                consolePs5 = req.optBoolean("console_ps5", true),
                gameStreamId = req.optString("game_id").ifEmpty { null },
                gameEntitlementId = req.optString("game_entitlement"),
                gameName = req.optString("game_name"),
                gameService = if (req.optString("game_service") == "psnow") "psnow" else "pscloud",
                gamePlatform = req.optString("game_platform"),
                width = req.optInt("width", 1280),
                height = req.optInt("height", 720),
                fps = req.optInt("fps", 30),
                bitrateKbps = req.optInt("bitrate_kbps", 5000),
                h265 = req.optString("codec") == "h265",
            )
        }
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ps-connect") }
    private val reaper = Executors.newSingleThreadExecutor { r -> Thread(r, "ps-teardown") }

    /** Guards the native handles: who owns the hole punch, whether a session exists. */
    private val lock = Any()
    /** Bumped by every start and stop; a callback or a connect step from an older one is ignored. */
    @Volatile private var generation = 0
    private var holepunch = 0L
    private var session = 0L

    @Volatile private var state = "idle"
    @Volatile private var kind = "console"
    @Volatile private var step: String? = null
    @Volatile private var detail: String? = null
    @Volatile private var error: String? = null

    val isRunning: Boolean get() = state == "connecting" || state == "streaming"

    fun statusJson(): JSONObject = JSONObject()
        .put("state", state)
        .put("kind", kind)
        .put("step", step ?: JSONObject.NULL)
        .put("detail", detail ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)

    private fun publish() = output.status(statusJson())

    fun start(req: Start) {
        val gen: Int
        synchronized(lock) {
            if (isRunning) return
            if (!PsNative.available) { setError("native_missing", if (req.cloud) "cloud" else "console"); return }
            gen = ++generation
            kind = if (req.cloud) "cloud" else "console"
            state = "connecting"
            step = if (req.cloud) "cloud" else "psn"
            detail = null
            error = null
        }
        publish()
        Log.i(TAG, "Starting ${if (req.cloud) "PS Plus cloud: ${req.gameName}" else "Remote Play"} (${req.width}x${req.height})")
        worker.execute { if (req.cloud) connectCloud(gen, req) else connect(gen, req) }
    }

    fun stop(reason: String) {
        val s: Long
        synchronized(lock) {
            generation++
            val hp = holepunch
            s = session
            session = 0L
            // A hole punch in progress stays with the connect thread, which finishes
            // it once the cancel below makes its current step return.
            if (hp != 0L) PsNative.holepunchCancel(hp)
            if (state != "idle") Log.i(TAG, "Stopping ($reason)")
            state = "idle"
            step = null
            detail = null
        }
        publish()
        if (s != 0L) reaper.execute { teardown(s) }
    }

    fun setPad(buttons: Int, l2: Int, r2: Int, lx: Int, ly: Int, rx: Int, ry: Int) {
        synchronized(lock) {
            if (session != 0L) PsNative.sessionSetController(session, buttons, l2, r2, lx, ly, rx, ry)
        }
    }

    fun requestKeyFrame() {
        synchronized(lock) {
            if (session != 0L && state == "streaming") PsNative.sessionRequestIdr(session)
        }
    }

    /** Puts the console in rest mode, which also ends the session. */
    fun consoleToRest() {
        synchronized(lock) {
            if (session != 0L) PsNative.sessionGotoBed(session)
        }
    }

    // ---------------------------------------------------------------- connecting

    private class Abort(val code: String) : Exception(code)

    private fun connect(gen: Int, req: Start) {
        fun cancelled() = gen != generation
        var hp = 0L
        try {
            val uid = req.consoleUid ?: throw Abort("not_configured")
            PsnAccount.installCaBundle(context)
            val token = PsnAccount.validToken(context)
            val accountId = Base64.decode(PsPreferences.getAccountId(context) ?: throw Abort("not_configured"), Base64.NO_WRAP)
            if (cancelled()) return
            step(gen, "console")

            hp = PsNative.holepunchInit(token)
            if (hp == 0L) throw Abort("psn")
            synchronized(lock) {
                if (cancelled()) throw Abort("cancelled")
                holepunch = hp
            }
            // UPnP only helps when the phone's own network has a gateway that speaks it.
            PsNative.holepunchUpnp(hp)
            check(gen, PsNative.holepunchCreate(hp), "psn", hp)
            check(gen, PsNative.holepunchOffer(hp), "psn", hp)
            check(gen, PsNative.holepunchStart(hp, hexToBytes(uid), if (req.consolePs5) PsNative.CONSOLE_PS5 else PsNative.CONSOLE_PS4), "console_unreachable", hp)
            check(gen, PsNative.holepunchPunch(hp, PsNative.PORT_CTRL), "network", hp)
            step(gen, "stream")

            synchronized(lock) {
                if (cancelled()) throw Abort("cancelled")
                // From here the session owns the hole punch, even when it fails to start.
                holepunch = 0L
                val handed = hp
                hp = 0L
                val s = PsNative.sessionCreate(handed, accountId, req.consolePs5, req.width, req.height, req.fps,
                    req.bitrateKbps, if (req.h265) PsNative.CODEC_H265 else PsNative.CODEC_H264, Callbacks(gen))
                if (s == 0L) throw Abort("session")
                begin(s)
            }
        } catch (e: Abort) {
            if (e.code != "cancelled") fail(gen, e.code)
        } catch (e: PsnAccount.PsnException) {
            fail(gen, if (e.code == "network") "network" else "signed_out")
        } catch (t: Throwable) {
            Log.e(TAG, "Remote Play connection failed", t)
            fail(gen, "unknown")
        } finally {
            if (hp != 0L) {
                synchronized(lock) { if (holepunch == hp) holepunch = 0L }
                PsNative.holepunchFini(hp)
            }
        }
    }

    /**
     * Sony's side first (authorisation, for PS Now the Kamaji claim of the game,
     * then a machine in the nearest datacenter that offers it, see
     * CloudStreamingBackend), then the stream itself, straight to that machine.
     */
    private fun connectCloud(gen: Int, req: Start) {
        fun cancelled() = gen != generation
        try {
            val gameId = req.gameStreamId ?: throw Abort("no_game")
            val npsso = PsPreferences.getNpsso(context) ?: throw Abort("not_configured")
            PsnAccount.installCaBundle(context)
            val prefs = com.tals.psbridge.cloud.Preferences(context)
            prefs.setQuality(req.height, req.bitrateKbps)
            val result = runBlocking {
                CloudStreamingBackend(context, prefs).startCompleteCloudSession(
                    serviceType = req.gameService,
                    gameIdentifier = gameId,
                    gameName = req.gameName.orEmpty(),
                    npssoToken = npsso,
                    ownedEntitlementId = req.gameEntitlementId.orEmpty(),
                    ownedPlatform = req.gamePlatform,
                    onProgress = { line ->
                        if (!cancelled() && state == "connecting") { detail = line; publish() }
                    },
                    isCancelled = { cancelled() }
                )
            }
            if (cancelled()) return
            val cloud = result.getOrElse { e -> throw Abort(cloudErrorCode(e)) }
            Log.i(TAG, "Cloud machine allocated in ${cloud.datacenter} (${cloud.rttMs} ms)")
            step(gen, "stream")
            synchronized(lock) {
                if (cancelled()) throw Abort("cancelled")
                val s = PsNative.sessionCreateCloud(
                    cloud.serverIp, cloud.serverPort, cloud.launchSpec, cloud.handshakeKey, cloud.sessionId,
                    cloud.psnWrapperType, cloud.mtuIn, cloud.mtuOut, cloud.rttMs.toLong() * 1000L,
                    cloud.serviceType == "pscloud",
                    cloud.platform == "ps5", req.width, req.height, 60, req.bitrateKbps,
                    // As the client asks. Both work from the PS5 cloud (measured); PS Now
                    // classics are H.264 whatever is asked.
                    if (req.h265 && cloud.serviceType == "pscloud") PsNative.CODEC_H265 else PsNative.CODEC_H264,
                    Callbacks(gen)
                )
                if (s == 0L) throw Abort("session")
                begin(s)
            }
        } catch (e: Abort) {
            if (e.code != "cancelled") fail(gen, e.code)
        } catch (t: Throwable) {
            Log.e(TAG, "PS Plus cloud connection failed", t)
            fail(gen, "unknown")
        }
    }

    /** Under [lock]: starts the session thread, or gives the session back and fails. */
    private fun begin(s: Long) {
        val err = PsNative.sessionStart(s)
        if (err != PsNative.OK) {
            reaper.execute { PsNative.sessionFree(s) }
            throw Abort("session")
        }
        session = s
    }

    private fun cloudErrorCode(e: Throwable): String {
        Log.w(TAG, "PS Plus cloud allocation failed: ${e.message}")
        return when (e) {
            is AuthorizationFailedException -> "signed_out"
            // Sony's 002.2001, which CloudPad files as a missing subscription, usually
            // says "Entitlement ID is not authorized for specific PSN User": the account
            // has Premium but not this game (a PS Plus game never added to the library).
            is PsPlusSubscriptionException ->
                if (e.message?.contains("not authorized", ignoreCase = true) == true) "game_unavailable" else "no_premium"
            is GameNotStreamableException -> "game_unavailable"
            is PingTimeoutException, is DatacenterNotOfferedException, is DistantDatacenterBatchException -> "datacenter"
            else -> "cloud"
        }
    }

    private fun check(gen: Int, err: Int, code: String, hp: Long) {
        if (gen != generation) throw Abort("cancelled")
        if (err != PsNative.OK) {
            Log.w(TAG, "Remote Play step failed: ${PsNative.errorString(err)} ${PsNative.holepunchLastError(hp).orEmpty()}")
            throw Abort(code)
        }
    }

    private fun step(gen: Int, step: String) {
        if (gen != generation || state != "connecting") return
        this.step = step
        publish()
    }

    /**
     * Ends the attempt or the session with an error the app words for the user:
     * not_configured, native_missing, signed_out, network, psn,
     * console_unreachable, session, in_use, console_off, link, unknown, and for
     * the cloud no_game, no_premium, game_unavailable, datacenter, cloud.
     */
    private fun fail(gen: Int, code: String) {
        val s: Long
        synchronized(lock) {
            if (gen != generation) return
            generation++
            s = session
            session = 0L
            state = "error"
            step = null
            detail = null
            error = code
        }
        Log.w(TAG, "Session ended: $code")
        publish()
        if (s != 0L) reaper.execute { teardown(s) }
    }

    private fun setError(code: String, kind: String) {
        this.kind = kind
        state = "error"
        step = null
        detail = null
        error = code
        publish()
    }

    private fun teardown(s: Long) {
        runCatching {
            PsNative.sessionStop(s)
            PsNative.sessionJoin(s)
            PsNative.sessionFree(s)
        }.onFailure { Log.w(TAG, "Session teardown failed", it) }
    }

    private inner class Callbacks(private val gen: Int) : PsNative.Listener {
        override fun onConnected() {
            if (gen != generation) return
            Log.i(TAG, "Session connected")
            if (state == "connecting") {
                state = "streaming"
                step = null
                publish()
            }
        }

        override fun onQuit(reason: Int, reasonString: String?) {
            Log.i(TAG, "Session quit: $reason ${reasonString.orEmpty()} (${PsNative.quitReasonString(reason)})")
            if (gen != generation) return
            when (reason) {
                PsNative.QUIT_STOPPED -> stop("server_stopped")
                PsNative.QUIT_RP_IN_USE -> fail(gen, "in_use")
                // The cloud machine ends the session when the game is quitted: not an error there.
                PsNative.QUIT_REMOTE_SHUTDOWN -> if (kind == "cloud") stop("cloud_ended") else fail(gen, "console_off")
                else -> fail(gen, "link")
            }
        }

        override fun onRumble(left: Int, right: Int) = Unit

        override fun onVideo(data: ByteArray, framesLost: Int) {
            if (gen == generation) output.video(data)
        }

        override fun onAudioFormat(channels: Int, rate: Int) {
            if (gen == generation) output.audioFormat(channels, rate)
        }

        override fun onAudio(pcm: ByteArray) {
            if (gen == generation) output.audio(pcm)
        }
    }

    private companion object {
        const val TAG = "RemoteSession"

        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
