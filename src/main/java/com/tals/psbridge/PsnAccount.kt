// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The PSN side of Remote Play over the internet: turning the user's sign-in
 * into the OAuth token chiaki's hole punching needs, keeping it fresh, and
 * listing the account's consoles.
 *
 * The flow and the client credentials are the ones of Sony's own desktop
 * Remote Play app, as used by chiaki-ng and CloudPad (whose PsnTokenManager
 * this follows): an `npsso` cookie from a signed-in browser is exchanged for an
 * authorisation code, the code for tokens, and the token for the account id.
 *
 * Every function here blocks on the network.
 */
object PsnAccount {
    private const val TAG = "PsnAccount"

    /** Where the user signs in, in their own browser (Sony refuses embedded WebViews). */
    const val SIGN_IN_URL = "https://www.playstation.com/"
    /** Opened in the same browser once signed in: it shows `{"npsso":"..."}`, which the user copies. */
    const val NPSSO_URL = "https://ca.account.sony.com/api/v1/ssocookie"

    private const val AUTHORIZE_URL = "https://ca.account.sony.com/api/authz/v3/oauth/authorize"
    private const val TOKEN_URL = "https://ca.account.sony.com/api/authz/v3/oauth/token"
    private const val ACCOUNT_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
    private const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val SCOPES = "psn:clientapp referenceDataService:countryConfig.read " +
        "pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val TIMEOUT_MS = 10_000

    /** A token closer than this to its expiry is refreshed before use. */
    private const val EXPIRY_MARGIN_MS = 5 * 60_000L

    class PsnException(val code: String, message: String) : IOException(message)

    /**
     * Accepts what the user pasted: the bare npsso, or the whole `{"npsso":"..."}`
     * line the browser shows (people tend to copy the line, quotes and all).
     */
    fun extractNpsso(pasted: String): String? {
        val trimmed = pasted.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed).optString("npsso") }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return trimmed.removeSurrounding("\"").takeIf { it.isNotBlank() && !it.contains(' ') }
    }

    /** Signs in with an npsso: tokens, then the account id. Throws [PsnException] with a code the UI words. */
    fun signIn(context: Context, npsso: String) {
        val tokens = exchangeNpsso(context, npsso)
        PsPreferences.saveTokens(context, npsso, tokens.access, tokens.refresh, tokens.expiresIn)
        val accountId = fetchAccountId(tokens.access)
            ?: throw PsnException("account", "PSN did not return the account id")
        PsPreferences.saveAccount(context, accountId, null)
        Log.i(TAG, "Signed in to PSN")
    }

    /**
     * A token valid for the next few minutes: the stored one, else a refreshed
     * one, else one from the stored npsso (which lasts about two months).
     */
    fun validToken(context: Context): String {
        val token = PsPreferences.getAccessToken(context)
        if (!token.isNullOrEmpty() && System.currentTimeMillis() + EXPIRY_MARGIN_MS < PsPreferences.getTokenExpiryMs(context)) {
            return token
        }
        refresh(context)?.let { return it }
        val npsso = PsPreferences.getNpsso(context) ?: throw PsnException("signed_out", "No PSN sign-in")
        val tokens = exchangeNpsso(context, npsso)
        PsPreferences.saveTokens(context, null, tokens.access, tokens.refresh, tokens.expiresIn)
        return tokens.access
    }

    /**
     * The account's PS5 consoles with Remote Play. PS5 only: chiaki's PSN device
     * listing refuses PS4 ("Only PS5 is supported by the list devices function").
     */
    fun listConsoles(context: Context): List<PsPreferences.Console> {
        val token = validToken(context)
        val flat = PsNative.listDevices(token, PsNative.CONSOLE_PS5)
            ?: throw PsnException("devices", "PSN did not list the consoles")
        val out = ArrayList<PsPreferences.Console>()
        for (i in 0 until flat.size / 4) {
            Log.i(TAG, "Console found: ${flat[i * 4]} (Remote Play ${if (flat[i * 4 + 2] == "1") "on" else "off"})")
            if (flat[i * 4 + 2] != "1") continue
            out.add(PsPreferences.Console(name = flat[i * 4], uidHex = flat[i * 4 + 1], ps5 = true))
        }
        return out
    }

    /**
     * The bundle of trusted roots chiaki's curl reads (mbedTLS cannot use
     * Android's store directly), written once per app version from the system
     * store, plus the one intermediate Sony's push-notification servers forget
     * to send (see the CloudPad note on COMODO RSA Domain Validation).
     */
    fun installCaBundle(context: Context) {
        val file = File(context.cacheDir, "ps-ca-bundle.pem")
        val marker = "# psbridge-ca-bundle 1\n"
        val fresh = runCatching { file.exists() && file.length() > 10_000 && file.bufferedReader().use { it.readLine() + "\n" } == marker }
            .getOrDefault(false)
        if (!fresh) {
            runCatching {
                file.bufferedWriter().use { out ->
                    out.write(marker)
                    out.write(COMODO_RSA_DV_INTERMEDIATE)
                    File("/system/etc/security/cacerts").listFiles()?.sortedBy { it.name }?.forEach { cert ->
                        runCatching { out.write(cert.readText()); out.write("\n") }
                    }
                }
            }.onFailure { Log.w(TAG, "CA bundle not written", it) }
        }
        PsNative.setCaBundle(file.absolutePath)
    }

    // ------------------------------------------------------------------ OAuth

    private class Tokens(val access: String, val refresh: String?, val expiresIn: Int)

    /** Authorisation code from the npsso cookie, then tokens; a stale code cannot be reused, so both retry together. */
    private fun exchangeNpsso(context: Context, npsso: String): Tokens {
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                val code = authorizationCode(npsso, PsPreferences.clientDuid(context))
                    ?: throw PsnException("npsso", "The npsso was refused")
                return exchangeCode(code)
            } catch (e: PsnException) {
                throw e
            } catch (e: IOException) {
                last = e
                Log.w(TAG, "PSN sign-in attempt ${attempt + 1} failed", e)
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw PsnException("network", last?.message ?: "network")
    }

    private fun authorizationCode(npsso: String, duid: String): String? {
        val params = listOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES,
            "response_type" to "code",
            "service_entity" to "urn:service-entity:psn",
            // A refresh token comes only with offline access.
            "access_type" to "offline",
            // The push-notification WebSocket used for hole punching checks it.
            "duid" to duid,
            "smcid" to "remoteplay",
            "layout_type" to "popup",
            "PlatformPrivacyWs1" to "minimal",
            "no_captcha" to "true",
            "cid" to UUID.randomUUID().toString()
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val conn = URL("$AUTHORIZE_URL?$params").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Cookie", "npsso=$npsso")
            val status = conn.responseCode
            val location = conn.getHeaderField("Location") ?: ""
            val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1)
            if (code == null) {
                // Sony says why in the redirect's query (error, error_code); the rest of
                // it is not logged, it can carry account details.
                val reason = Regex("[?&#](error(?:_code|_description)?)=([^&]*)").findAll(location)
                    .joinToString(" ") { "${it.groupValues[1]}=${it.groupValues[2]}" }
                Log.w(TAG, "Authorize answered $status without a code: ${reason.ifEmpty { location.substringBefore('?') }}")
            }
            return code
        } finally {
            conn.disconnect()
        }
    }

    private fun exchangeCode(code: String): Tokens {
        val body = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val json = post(TOKEN_URL, body) ?: throw PsnException("token", "Token exchange refused")
        return tokensFrom(json) ?: throw PsnException("token", "No token in the answer")
    }

    private fun refresh(context: Context): String? {
        val refresh = PsPreferences.getRefreshToken(context) ?: return null
        val body = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "scope" to SCOPES,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val tokens = runCatching { post(TOKEN_URL, body)?.let { tokensFrom(it) } }.getOrNull() ?: return null
        PsPreferences.saveTokens(context, null, tokens.access, tokens.refresh, tokens.expiresIn)
        return tokens.access
    }

    private fun tokensFrom(json: String): Tokens? {
        val o = JSONObject(json)
        val access = o.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
        return Tokens(access, o.optString("refresh_token").takeIf { it.isNotEmpty() }, o.optInt("expires_in", 3600))
    }

    /** The numeric user id, as the 8 little-endian bytes Remote Play wants, base64. */
    private fun fetchAccountId(accessToken: String): String? {
        val basic = Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
        val conn = URL("$ACCOUNT_URL/$accessToken").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Basic $basic")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val userId = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("user_id")
                .toLongOrNull() ?: return null
            val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(userId).array()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "POST $url answered ${conn.responseCode}")
                return null
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /**
     * COMODO RSA Domain Validation Secure Server CA. Sony's push-notification
     * servers do not send it, and mbedTLS does not fetch missing intermediates.
     */
    private const val COMODO_RSA_DV_INTERMEDIATE = """-----BEGIN CERTIFICATE-----
MIIGCDCCA/CgAwIBAgIQKy5u6tl1NmwUim7bo3yMBzANBgkqhkiG9w0BAQwFADCB
hTELMAkGA1UEBhMCR0IxGzAZBgNVBAgTEkdyZWF0ZXIgTWFuY2hlc3RlcjEQMA4G
A1UEBxMHU2FsZm9yZDEaMBgGA1UEChMRQ09NT0RPIENBIExpbWl0ZWQxKzApBgNV
BAMTIkNPTU9ETyBSU0EgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkwHhcNMTQwMjEy
MDAwMDAwWhcNMjkwMjExMjM1OTU5WjCBkDELMAkGA1UEBhMCR0IxGzAZBgNVBAgT
EkdyZWF0ZXIgTWFuY2hlc3RlcjEQMA4GA1UEBxMHU2FsZm9yZDEaMBgGA1UEChMR
Q09NT0RPIENBIExpbWl0ZWQxNjA0BgNVBAMTLUNPTU9ETyBSU0EgRG9tYWluIFZh
bGlkYXRpb24gU2VjdXJlIFNlcnZlciBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEP
ADCCAQoCggEBAI7CAhnhoFmk6zg1jSz9AdDTScBkxwtiBUUWOqigwAwCfx3M28Sh
bXcDow+G+eMGnD4LgYqbSRutA776S9uMIO3Vzl5ljj4Nr0zCsLdFXlIvNN5IJGS0
Qa4Al/e+Z96e0HqnU4A7fK31llVvl0cKfIWLIpeNs4TgllfQcBhglo/uLQeTnaG6
ytHNe+nEKpooIZFNb5JPJaXyejXdJtxGpdCsWTWM/06RQ1A/WZMebFEh7lgUq/51
UHg+TLAchhP6a5i84DuUHoVS3AOTJBhuyydRReZw3iVDpA3hSqXttn7IzW3uLh0n
c13cRTCAquOyQQuvvUSH2rnlG51/ruWFgqUCAwEAAaOCAWUwggFhMB8GA1UdIwQY
MBaAFLuvfgI9+qbxPISOre44mOzZMjLUMB0GA1UdDgQWBBSQr2o6lFoL2JDqElZz
30O0Oija5zAOBgNVHQ8BAf8EBAMCAYYwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNV
HSUEFjAUBggrBgEFBQcDAQYIKwYBBQUHAwIwGwYDVR0gBBQwEjAGBgRVHSAAMAgG
BmeBDAECATBMBgNVHR8ERTBDMEGgP6A9hjtodHRwOi8vY3JsLmNvbW9kb2NhLmNv
bS9DT01PRE9SU0FDZXJ0aWZpY2F0aW9uQXV0aG9yaXR5LmNybDBxBggrBgEFBQcB
AQRlMGMwOwYIKwYBBQUHMAKGL2h0dHA6Ly9jcnQuY29tb2RvY2EuY29tL0NPTU9E
T1JTQUFkZFRydXN0Q0EuY3J0MCQGCCsGAQUFBzABhhhodHRwOi8vb2NzcC5jb21v
ZG9jYS5jb20wDQYJKoZIhvcNAQEMBQADggIBAE4rdk+SHGI2ibp3wScF9BzWRJ2p
mj6q1WZmAT7qSeaiNbz69t2Vjpk1mA42GHWx3d1Qcnyu3HeIzg/3kCDKo2cuH1Z/
e+FE6kKVxF0NAVBGFfKBiVlsit2M8RKhjTpCipj4SzR7JzsItG8kO3KdY3RYPBps
P0/HEZrIqPW1N+8QRcZs2eBelSaz662jue5/DJpmNXMyYE7l3YphLG5SEXdoltMY
dVEVABt0iN3hxzgEQyjpFv3ZBdRdRydg1vs4O2xyopT4Qhrf7W8GjEXCBgCq5Ojc
2bXhc3js9iPc0d1sjhqPpepUfJa3w/5Vjo1JXvxku88+vZbrac2/4EjxYoIQ5QxG
V/Iz2tDIY+3GH5QFlkoakdH368+PUq4NCNk+qKBR6cGHdNXJ93SrLlP7u3r7l+L4
HyaPs9Kg4DdbKDsx5Q5XLVq4rXmsXiBmGqW5prU5wfWYQ//u+aen/e7KJD2AFsQX
j4rBYKEMrltDR5FL1ZoXX/nUh8HCjLfn4g8wGTeGrODcQgPmlKidrv0PJFGUzpII
0fxQ8ANAe4hZ7Q7drNJ3gjTcBpUC2JD5Leo31Rpg0Gcg19hCC0Wvgmje3WYkN5Ap
lBlGGSW4gNfL1IYoakRwJiNiqZ+Gb7+6kHDSVneFeO/qJakXzlByjAA6quPbYzSf
+AZxAeKCINT+b72x
-----END CERTIFICATE-----
"""
}
