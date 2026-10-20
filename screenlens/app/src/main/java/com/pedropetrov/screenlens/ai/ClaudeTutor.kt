package com.pedropetrov.screenlens.ai

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** What the user wants the controller screen to present. */
enum class Mode(val label: String, val instruction: String) {
    SOLUTION(
        "Solution",
        "Give me the SOLUTION for what is shown. If it is a question or exercise, start with the answer " +
            "(for multiple choice: the correct option letters), then explain briefly why, and why the other " +
            "options are wrong in one line each. If it is an error, fault or problem, give a troubleshooting " +
            "plan: most likely causes first, then numbered steps with what to check after each step.",
    ),
    EXPLANATION(
        "Explanation",
        "EXPLAIN the topic shown as a short lesson: what it is, the key ideas, a worked example using what is " +
            "in the image, common mistakes, and finish with 3 quick questions to check understanding.",
    ),
}

/**
 * One conversation with Claude about one captured image. Not thread-safe: call
 * [start] and [followUp] one at a time from a background thread.
 */
class ClaudeTutor(apiKey: String, private val tier: Tier) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    // Assistant turns are stored exactly as returned (including thinking blocks),
    // which multi-turn conversations require.
    private val history = mutableListOf<BetaMessageParam>()

    /** Sends the photo with the chosen mode. Streams text to [onText]. */
    fun start(jpeg: ByteArray, mode: Mode, extra: String, onText: (String) -> Unit): Outcome {
        history.clear()
        val image = BetaBase64ImageSource.builder()
            .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
            .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .build()
        val request = buildString {
            append(mode.instruction)
            if (extra.isNotBlank()) append("\n\nExtra instructions from me: ").append(extra.trim())
        }
        history += BetaMessageParam.builder()
            .role(BetaMessageParam.Role.USER)
            .contentOfBetaContentBlockParams(
                listOf(BetaContentBlockParam.ofImage(image), BetaContentBlockParam.ofText(request))
            )
            .build()
        return run(onText)
    }

    /** Asks a follow-up question about the same image. */
    fun followUp(question: String, onText: (String) -> Unit): Outcome {
        check(history.isNotEmpty()) { "start() must be called first" }
        history += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(question).build()
        return run(onText)
    }

    private fun run(onText: (String) -> Unit): Outcome {
        val builder = MessageCreateParams.builder()
            .model(tier.model)
            .maxTokens(16_000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(BetaOutputConfig.builder().effort(tier.effort).build())
            // Caches the photo so follow-up questions re-read it at a tenth of the price.
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .messages(history.toList())
        if (tier.serverFallback) {
            // If a safety classifier declines, the API retries on a suitable fallback model.
            builder.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        }
        val params = builder.build()

        val accumulator = BetaMessageAccumulator.create()
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                stream.stream().forEach { event ->
                    accumulator.accumulate(event)
                    event.contentBlockDelta()
                        .flatMap { it.delta().text() }
                        .ifPresent { onText(it.text()) }
                }
            }
        } catch (e: Exception) {
            history.removeAt(history.lastIndex) // let the user retry the same turn
            throw e
        }

        val message = accumulator.message()
        history += message.toParam()
        val usage = message.usage()
        val cacheWrite = usage.cacheCreationInputTokens().orElse(0L)
        val cacheRead = usage.cacheReadInputTokens().orElse(0L)
        return Outcome(
            refused = message.stopReason().orElse(null) == BetaStopReason.REFUSAL,
            inputTokens = usage.inputTokens() + cacheWrite + cacheRead,
            outputTokens = usage.outputTokens(),
            costUsd = tier.costUsd(usage.inputTokens(), cacheWrite, cacheRead, usage.outputTokens()),
        )
    }

    data class Outcome(val refused: Boolean, val inputTokens: Long, val outputTokens: Long, val costUsd: Double)

    companion object {

        private val SYSTEM_PROMPT = """
            You are ScreenLens, a tutor and troubleshooting assistant. The user pointed a phone camera at a
            screen, a book page or handwritten notes and took a photo. Your answer is shown on a second phone.

            - Read the image carefully. If part of it is unreadable (glare, blur, cut off), say which part and
              how to retake the photo (angle, distance, light), then answer as far as the readable part allows.
            - Answer in the language the user writes in. If they only chose a mode, use the language of the
              content in the image.
            - Be concise: lead with the answer, skip restating the question, and keep it under about 300 words
              unless the user asks for more.
            - Format for a phone screen: short paragraphs, "##" headings, bullet or numbered lists, **bold**
              for key terms, code in ``` blocks. No tables.
            - For troubleshooting, flag safety risks (electricity, gas, heat, data loss) before the steps.
        """.trimIndent()
    }
}
