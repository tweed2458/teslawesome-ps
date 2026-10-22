// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.tals.psbridge.cloud.PsCloudCatalogService
import com.tals.psbridge.cloud.PsCloudOwnership
import com.tals.psbridge.cloud.PsnCatalogService
import com.tals.psbridge.cloud.PsnResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The bridge's only entry point: a service with no screen.
 *
 * The client app binds to it, which keeps the bridge alive exactly as long as
 * the client needs it, then hands it one end of a socket pair it created
 * (Binder transaction [Protocol.TRANSACTION_CONNECT]), over which the two talk
 * ([Protocol]). Apps cannot reach each other's sockets directly since Android 9
 * (per-app SELinux categories); a file descriptor passed through Binder is the
 * sanctioned way. One client at a time, and only an app signed with this app's
 * own key: any other hand-over is refused.
 */
class ModuleService : Service() {

    @Volatile private var client: Client? = null
    private lateinit var session: RemoteSession
    private val requests = Executors.newSingleThreadExecutor { r -> Thread(r, "ps-requests") }

    override fun onCreate() {
        super.onCreate()
        session = RemoteSession(this, object : RemoteSession.Output {
            override fun status(status: JSONObject) { client?.send(Protocol.STATUS, status.toString().toByteArray()) }
            override fun video(sample: ByteArray) { client?.send(Protocol.VIDEO, sample) }
            override fun audioFormat(channels: Int, rate: Int) {
                client?.send(Protocol.AUDIO_FORMAT, ByteBuffer.allocate(8).putInt(channels).putInt(rate).array())
            }
            override fun audio(pcm: ByteArray) { client?.send(Protocol.AUDIO, pcm) }
        })
        Log.i(TAG, "Bridge ready")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        session.stop("module_stopped")
        client?.close()
        super.onDestroy()
    }

    /** Receives the client's end of the socket pair. */
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != Protocol.TRANSACTION_CONNECT) return super.onTransact(code, data, reply, flags)
            val uid = Binder.getCallingUid()
            if (!isTrustedPeer(uid)) {
                Log.w(TAG, "Connection refused: caller not signed with this app's key")
                return false
            }
            val fd = data.readFileDescriptor() ?: return false
            // A new connection replaces the old one (the client reconnected).
            client?.close()
            val c = Client(fd)
            client = c
            Thread({ c.readLoop() }, "ps-client").start()
            reply?.writeInt(Protocol.VERSION)
            return true
        }
    }

    /** The caller is another app signed with this app's own key. */
    private fun isTrustedPeer(uid: Int): Boolean {
        if (uid == Process.myUid()) return false
        return packageManager.checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH
    }

    private inner class Client(private val fd: ParcelFileDescriptor) {
        private val input = DataInputStream(FileInputStream(fd.fileDescriptor).buffered())
        private val output = DataOutputStream(BufferedOutputStream(FileOutputStream(fd.fileDescriptor), 64 * 1024))
        @Volatile private var open = true

        fun send(type: Int, payload: ByteArray) {
            if (!open) return
            synchronized(output) {
                runCatching { Protocol.write(output, type, payload) }.onFailure { close() }
            }
        }

        fun close() {
            if (!open) return
            open = false
            runCatching { fd.close() }
        }

        fun readLoop() {
            try {
                while (open) {
                    val frame = Protocol.read(input)
                    when (frame.type) {
                        Protocol.PAD -> {
                            val b = ByteBuffer.wrap(frame.payload)
                            if (frame.payload.size >= 28) session.setPad(b.int, b.int, b.int, b.int, b.int, b.int, b.int)
                        }
                        Protocol.KEYFRAME -> session.requestKeyFrame()
                        Protocol.REQUEST -> {
                            val req = JSONObject(String(frame.payload))
                            requests.execute { send(Protocol.RESPONSE, handle(req).toString().toByteArray()) }
                        }
                    }
                }
            } catch (_: Exception) {
                // The app went away.
            } finally {
                close()
                if (client === this) {
                    client = null
                    // Nobody left to send the picture to.
                    session.stop("app_disconnected")
                }
            }
        }
    }

    /** Answers one request. Network calls block here, on the request thread. */
    private fun handle(req: JSONObject): JSONObject {
        val reply = JSONObject().put("id", req.optInt("id"))
        try {
            when (req.optString("op")) {
                "hello" -> reply.put("ok", true)
                    .put("version", Protocol.VERSION)
                    .put("native", PsNative.available)
                    .put("signed_in", PsPreferences.isSignedIn(this))
                    .put("status", session.statusJson())
                "sign_in" -> {
                    val npsso = PsnAccount.extractNpsso(req.optString("code"))
                        ?: return reply.put("ok", false).put("error", "npsso")
                    PsnAccount.installCaBundle(this)
                    PsnAccount.signIn(this, npsso)
                    reply.put("ok", true)
                }
                "sign_out" -> {
                    PsPreferences.signOut(this)
                    reply.put("ok", true)
                }
                "consoles" -> {
                    PsnAccount.installCaBundle(this)
                    val list = JSONArray()
                    PsnAccount.listConsoles(this).forEach {
                        list.put(JSONObject().put("name", it.name).put("uid", it.uidHex).put("ps5", it.ps5))
                    }
                    reply.put("ok", true).put("consoles", list)
                }
                "cloud_library" -> {
                    // The account's own library, as CloudPad launches from: Sony refuses to
                    // stream a catalog game that is not in it. Its catalog only answers
                    // lowercase locales (fr-fr, not fr-FR).
                    val npsso = PsPreferences.getNpsso(this) ?: return reply.put("ok", false).put("error", "signed_out")
                    val locale = com.tals.psbridge.cloud.Preferences(this).getCloudStoreLocale().lowercase()
                    val games = kotlinx.coroutines.runBlocking { PsCloudCatalogService().fetchOwnedPs5Games(npsso, locale) }
                    val list = JSONArray()
                    games.filter { PsCloudOwnership.streamServiceType(it) == "pscloud" }
                        .distinctBy { PsCloudOwnership.streamingIdentifier(it) }
                        .sortedBy { it.name.lowercase() }
                        .forEach {
                            list.put(JSONObject()
                                .put("name", it.name)
                                .put("id", PsCloudOwnership.streamingIdentifier(it))
                                .put("entitlement", it.entitlementId)
                                .put("image", it.imageUrl)
                                .put("service", "pscloud")
                                .put("platform", "ps5"))
                        }
                    reply.put("ok", true).put("games", list)
                }
                "cloud_catalog_ps5" -> {
                    // PS5 games Sony streams that come with PS Plus (Game Catalog, monthly
                    // games, Ubisoft+ Classics) or are free to play. Not playable until added
                    // to the account's library: the client says how.
                    val locale = com.tals.psbridge.cloud.Preferences(this).getCloudStoreLocale().lowercase()
                    val catalog = kotlinx.coroutines.runBlocking { PsCloudCatalogService().fetchPs5CloudCatalog(locale) }
                    val list = JSONArray()
                    catalog.browseGames
                        .filter { PsCloudOwnership.streamServiceType(it) == "pscloud" && (it.plusCatalog || it.psCatalog || it.freeToPlay) }
                        .distinctBy { it.productId }
                        .sortedBy { it.name.lowercase() }
                        .forEach {
                            list.put(JSONObject()
                                .put("name", it.name)
                                .put("id", it.productId)
                                .put("entitlement", "")
                                .put("image", it.imageUrl)
                                .put("service", "ps5catalog")
                                .put("platform", "ps5")
                                .put("store", it.conceptUrl))
                        }
                    reply.put("ok", true).put("games", list)
                }
                "cloud_catalog" -> {
                    // The PS Plus classics catalog (PS4 and PS3, the former PS Now): no
                    // need to own them, the launch claims the game for the subscription.
                    val npsso = PsPreferences.getNpsso(this) ?: return reply.put("ok", false).put("error", "signed_out")
                    val prefs = com.tals.psbridge.cloud.Preferences(this)
                    val result = kotlinx.coroutines.runBlocking { PsnCatalogService(prefs).fetchPsnowCatalog(npsso) }
                    val games = when (result) {
                        is PsnResult.Success -> result.data
                        is PsnResult.Error -> return reply.put("ok", false).put("error", "cloud")
                    }
                    val list = JSONArray()
                    games.distinctBy { it.productId }.sortedBy { it.name.lowercase() }.forEach {
                        list.put(JSONObject()
                            .put("name", it.name)
                            .put("id", it.productId)
                            .put("entitlement", it.entitlementId)
                            .put("image", it.imageUrl)
                            .put("service", "psnow")
                            .put("platform", it.platform))
                    }
                    reply.put("ok", true).put("games", list)
                }
                "start" -> {
                    session.start(RemoteSession.Start.from(req))
                    reply.put("ok", true)
                }
                "stop" -> {
                    session.stop(req.optString("reason", "app_stop"))
                    reply.put("ok", true)
                }
                "rest" -> {
                    session.consoleToRest()
                    reply.put("ok", true)
                }
                else -> reply.put("ok", false).put("error", "unknown_op")
            }
        } catch (e: PsnAccount.PsnException) {
            reply.put("ok", false).put("error", e.code)
        } catch (e: Exception) {
            Log.w(TAG, "Request ${req.optString("op")} failed", e)
            reply.put("ok", false).put("error", "failed")
        }
        return reply
    }

    private companion object {
        const val TAG = "PsBridge"
    }
}
