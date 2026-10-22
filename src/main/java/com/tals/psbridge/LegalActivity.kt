// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The "Appropriate Legal Notices" of the AGPL-3.0 (§0, §5 d): the copyrights,
 * that there is no warranty, that the app may be passed on under this licence,
 * the licence itself, and where the source is (§6).
 *
 * Exported: TeslAwesome's PlayStation card opens it, since the launcher icon
 * opens TeslAwesome straight away when it is installed.
 */
class LegalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(value: CharSequence, size: Float, bottom: Int = pad / 2, mono: Boolean = false) =
            column.addView(TextView(this).apply {
                text = value
                textSize = size
                setPadding(0, 0, 0, bottom)
                setTextIsSelectable(true)
                if (mono) typeface = Typeface.MONOSPACE
            })

        text(getString(R.string.legal_title), 22f)
        text(getString(R.string.legal_copyright), 15f)
        text(getString(R.string.legal_warranty), 15f)
        text(getString(R.string.legal_source), 15f)
        column.addView(Button(this).apply {
            text = getString(R.string.about_source)
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.SOURCE_URL))) }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad })
        text(getString(R.string.legal_licence), 18f)
        // The licence as shipped with the source (COPYING, copied in at build time).
        val licence = runCatching { assets.open("COPYING").bufferedReader().use { it.readText() } }.getOrNull()
        if (licence != null) text(licence, 11f, 0, mono = true)

        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(ScrollView(this@LegalActivity).apply { addView(column) })
        })
    }
}
