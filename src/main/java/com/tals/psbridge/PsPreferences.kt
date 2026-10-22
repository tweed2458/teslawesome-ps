// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

import android.content.Context
import java.security.SecureRandom

/**
 * What the module keeps between sessions: the PSN sign-in (npsso, OAuth tokens,
 * account id) and this phone's device id towards PSN. Choices (console, game,
 * quality) belong to the app and arrive with each request.
 *
 * Private to the module, excluded from backups (allowBackup=false): the
 * credentials never leave the phone.
 */
object PsPreferences {
    private const val PREFS = "psn"

    private const val KEY_NPSSO = "npsso"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_TOKEN_EXPIRY = "token_expiry_ms"
    private const val KEY_ACCOUNT_ID = "account_id"
    private const val KEY_CLIENT_DUID = "client_duid"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getNpsso(context: Context): String? = prefs(context).getString(KEY_NPSSO, null)
    fun getAccessToken(context: Context): String? = prefs(context).getString(KEY_ACCESS_TOKEN, null)
    fun getRefreshToken(context: Context): String? = prefs(context).getString(KEY_REFRESH_TOKEN, null)
    fun getTokenExpiryMs(context: Context): Long = prefs(context).getLong(KEY_TOKEN_EXPIRY, 0L)

    /** The PSN account id as Remote Play wants it: 8 bytes, little-endian, base64. */
    fun getAccountId(context: Context): String? = prefs(context).getString(KEY_ACCOUNT_ID, null)

    fun saveTokens(context: Context, npsso: String?, access: String, refresh: String?, expiresInS: Int) {
        prefs(context).edit().apply {
            if (npsso != null) putString(KEY_NPSSO, npsso)
            putString(KEY_ACCESS_TOKEN, access)
            if (!refresh.isNullOrEmpty()) putString(KEY_REFRESH_TOKEN, refresh)
            putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInS * 1000L)
        }.apply()
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveAccount(context: Context, accountId: String, onlineId: String?) {
        prefs(context).edit().putString(KEY_ACCOUNT_ID, accountId).apply()
    }

    fun isSignedIn(context: Context): Boolean =
        !getAccessToken(context).isNullOrEmpty() && !getAccountId(context).isNullOrEmpty()

    /** Forgets the account, keeps this phone's DUID. */
    fun signOut(context: Context) {
        prefs(context).edit()
            .remove(KEY_NPSSO).remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY).remove(KEY_ACCOUNT_ID)
            .apply()
    }

    /**
     * This phone's device id towards PSN, made once. Sony ties the Remote Play
     * token to it, so it must not change between sign-ins. Same shape as chiaki's
     * chiaki_holepunch_generate_client_device_uid.
     */
    fun clientDuid(context: Context): String {
        prefs(context).getString(KEY_CLIENT_DUID, null)?.let { return it }
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val duid = "0000000700410080" + random.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_CLIENT_DUID, duid).apply()
        return duid
    }

    /** A console of the account, as PSN lists it. */
    data class Console(val name: String, val uidHex: String, val ps5: Boolean)
}
