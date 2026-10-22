// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.tals.psbridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * What the module's launcher icon opens. The module has no screen of its own
 * use: with TeslAwesome installed, a launch opens TeslAwesome, this page staying
 * behind it. Without it, this page says what the module is for and leads to
 * TeslAwesome on Google Play. Either way the legal notices stay one tap away: a
 * button here, and TeslAwesome's PlayStation card opens [LegalActivity] too.
 */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val teslAwesome = packageManager.getLaunchIntentForPackage(TESLAWESOME)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // The app's logo, large and centred. Not the launcher icon: as an adaptive
        // icon its artwork fills barely half of it, inside a dark square.
        val logoWidth = (200 * resources.displayMetrics.density).toInt()
        logoArtwork()?.let { art ->
            layout.addView(ImageView(this).apply {
                setImageBitmap(art)
                adjustViewBounds = true
                contentDescription = null
            }, LinearLayout.LayoutParams(logoWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = pad
            })
        }
        layout.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setPadding(0, 0, 0, pad / 2)
        })
        layout.addView(TextView(this).apply {
            text = getString(R.string.about_text)
            textSize = 16f
            setPadding(0, 0, 0, pad)
        })
        layout.addView(Button(this).apply {
            if (teslAwesome != null) {
                text = getString(R.string.open_teslawesome)
                setOnClickListener { runCatching { startActivity(teslAwesome) } }
            } else {
                text = getString(R.string.get_teslawesome)
                setOnClickListener { openStore() }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            // Set in from the text's edges, so it does not run the page's full width.
            marginStart = pad
            marginEnd = pad
        })

        // The legal notices, at the bottom of the page.
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        page.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.legal_title)
            textSize = 14f
            setOnClickListener { startActivity(Intent(this@AboutActivity, LegalActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = pad / 2
        })

        // No title bar: a frame keeps the page clear of the status and navigation
        // bars. Not the layout itself: fitsSystemWindows replaces a view's own
        // padding with the bars' insets, which left the text against the edges.
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })

        // A fresh launch goes straight on to TeslAwesome, in its own task. This page
        // stays behind it rather than closing: back to it from the recent apps (or
        // the icon again) it shows, without sending the user away a second time.
        if (savedInstanceState == null && teslAwesome != null) runCatching { startActivity(teslAwesome) }
    }

    /** Google Play's app when there is one, its web page otherwise. */
    private fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TESLAWESOME"))
        try {
            startActivity(market)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL))) }
        }
    }

    /**
     * The launcher artwork without its background: cropped to what differs from
     * the corner colour, so no dark square shows on the page's own background.
     */
    private fun logoArtwork(): Bitmap? {
        // Not scaled by the screen density: the PNG is already 768 px.
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val src = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_image, opts) ?: return null
        val w = src.width
        val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val bg = px[0]
        fun differs(c: Int): Boolean {
            val d = abs(Color.red(c) - Color.red(bg)) + abs(Color.green(c) - Color.green(bg)) + abs(Color.blue(c) - Color.blue(bg))
            return Color.alpha(c) > 16 && d > 40
        }
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (differs(px[y * w + x])) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return src
        // The background inside the artwork becomes transparent too.
        val out = IntArray(w * h) { if (differs(px[it])) px[it] else Color.TRANSPARENT }
        val cut = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(cut, left, top, right - left + 1, bottom - top + 1)
    }

    companion object {
        /** The app this module serves. */
        const val TESLAWESOME = "com.tals.teslawesome"
        const val STORE_URL = "https://play.google.com/store/apps/details?id=$TESLAWESOME"

        /** Where the complete source of this version is published (AGPL-3.0 §6). */
        const val SOURCE_URL = "https://github.com/tweed2458/teslawesome-ps"
    }
}
