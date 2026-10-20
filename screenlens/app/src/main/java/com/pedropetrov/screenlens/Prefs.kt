package com.pedropetrov.screenlens

import android.content.Context
import com.pedropetrov.screenlens.ai.Tier
import kotlin.random.Random

/**
 * Small key/value store for test-phase settings.
 *
 * Phase 1 keeps the Claude API key on the controller phone. Before shipping to
 * real users, move the key to a backend you control instead.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("screenlens", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value.trim()).apply()

    var tier: Tier
        get() = Tier.entries.firstOrNull { it.name == sp.getString("tier", null) } ?: Tier.ECONOMY
        set(value) = sp.edit().putString("tier", value.name).apply()

    var lastHost: String
        get() = sp.getString("last_host", "") ?: ""
        set(value) = sp.edit().putString("last_host", value.trim()).apply()

    var lastPin: String
        get() = sp.getString("last_pin", "") ?: ""
        set(value) = sp.edit().putString("last_pin", value.trim()).apply()

    /** PIN the camera phone requires from controllers. Generated once per install. */
    val cameraPin: String
        get() = sp.getString("camera_pin", null) ?: Random.nextInt(100_000, 1_000_000).toString().also {
            sp.edit().putString("camera_pin", it).apply()
        }
}
