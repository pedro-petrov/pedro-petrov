package com.pedropetrov.screenlens.ai

import com.anthropic.models.beta.messages.BetaOutputConfig

/**
 * Model + effort presets, cheapest first. Prices are USD per million tokens
 * (Haiku's rate applies to prompts up to 100K tokens, which covers one photo).
 */
enum class Tier(
    val label: String,
    val hint: String,
    val model: String,
    val effort: BetaOutputConfig.Effort,
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    val serverFallback: Boolean,
) {
    ECONOMY(
        "Economy", "Claude Haiku 5.5 · about 0.2¢ per photo · good for most exam questions",
        "claude-haiku-5-5", BetaOutputConfig.Effort.MEDIUM, 0.10, 0.50, serverFallback = false,
    ),
    BALANCED(
        "Balanced", "Claude Sonnet 5.5 · about 2–3¢ per photo",
        "claude-sonnet-5-5", BetaOutputConfig.Effort.MEDIUM, 2.0, 10.0, serverFallback = true,
    ),
    BEST(
        "Best", "Claude Opus 5.5 · about 6–10¢ per photo · hardest problems",
        "claude-opus-5-5", BetaOutputConfig.Effort.HIGH, 4.0, 20.0, serverFallback = true,
    ),
    ;

    /** Cost of one response. Cache writes are billed at 1.25× input, cache reads at 0.1×. */
    fun costUsd(input: Long, cacheWrite: Long, cacheRead: Long, output: Long): Double =
        (input + cacheWrite * 1.25 + cacheRead * 0.1) * inputPerMTok / 1e6 + output * outputPerMTok / 1e6
}
