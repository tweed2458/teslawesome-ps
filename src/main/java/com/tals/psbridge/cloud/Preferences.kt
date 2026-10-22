// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge.cloud

import android.content.Context

/**
 * The slice of CloudPad's Preferences its cloud code reads, so those files stay
 * as close to upstream as possible. The stream quality is the one the app asked
 * for in its last cloud start ([setQuality]); the rest are CloudPad's defaults:
 * automatic datacenter, store locale from the phone, retry on.
 */
class Preferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** One per install, like the Remote Play DUID: a new one per launch looks like a new device to Sony. */
    var cloudDuid: String
        get() = prefs.getString("cloud_duid", "") ?: ""
        set(value) { prefs.edit().putString("cloud_duid", value).apply() }

    fun getCloudStoreLocale(): String {
        prefs.getString("session_store_locale", null)?.let { return it }
        val locale = java.util.Locale.getDefault()
        val language = locale.language.takeIf { it.isNotEmpty() } ?: "en"
        val country = locale.country.takeIf { it.isNotEmpty() } ?: "US"
        return "$language-${country.uppercase()}"
    }

    // The PS Now catalog and Kamaji learn the account's real store region from Sony
    // and keep it here, as CloudPad does.
    fun getCloudLanguage(): String = getCloudStoreLocale()
    fun getCloudResolvedStoreCountry(): String = prefs.getString("resolved_store_country", "") ?: ""
    fun setCloudResolvedStoreCountry(country: String) = prefs.edit().putString("resolved_store_country", country).apply()
    fun getCloudResolvedStoreLang(): String = prefs.getString("resolved_store_lang", "") ?: ""
    fun setCloudResolvedStoreLang(lang: String) = prefs.edit().putString("resolved_store_lang", lang).apply()
    fun setCloudCatalogNativeMode(nativeMode: Boolean) = prefs.edit().putBoolean("catalog_native_mode", nativeMode).apply()
    fun setCloudStoreLocaleFromSession(language: String?, country: String?) {
        CloudLocale.fromSession(language, country)?.let { prefs.edit().putString("session_store_locale", it).apply() }
    }

    /** Empty: Gaikai derives the game's language from the store locale. */
    fun getCloudGameLanguage(): String = ""

    fun setQuality(height: Int, bitrateKbps: Int) {
        prefs.edit().putInt("resolution", height).putInt("bitrate", bitrateKbps).apply()
    }

    fun getCloudResolutionPscloud(): Int = prefs.getInt("resolution", 720)
    fun getCloudResolutionPsnow(): Int = getCloudResolutionPscloud()
    fun getCloudBitratePscloud(): Int = prefs.getInt("bitrate", 10_000)
    fun getCloudBitratePsnow(): Int = getCloudBitratePscloud()

    fun getCloudDatacenterPscloud(): String = "Auto"
    fun getCloudDatacenterPsnow(): String = "Auto"

    /** On: a distant batch of datacenters is retried in a new session before settling for it. */
    fun getCloudDatacenterRetryPscloud(): Boolean = true
    fun getCloudDatacenterRetryPsnow(): Boolean = true

    fun getCloudDatacentersJsonPscloud(): String = prefs.getString("datacenters_pscloud", "") ?: ""
    fun setCloudDatacentersJsonPscloud(json: String) = prefs.edit().putString("datacenters_pscloud", json).apply()
    fun getCloudDatacentersJsonPsnow(): String = prefs.getString("datacenters_psnow", "") ?: ""
    fun setCloudDatacentersJsonPsnow(json: String) = prefs.edit().putString("datacenters_psnow", json).apply()
}
