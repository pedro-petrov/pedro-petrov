package com.pedropetrov.screenlens.ai

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** What the user wants the controller screen to present. */
enum class Mode(val label: String, val instruction: String) {
    SOLUTION(
        "Solution",
        "Give me the SOLUTION for what is shown. If it is a question or exercise, solve it step by step and " +
            "state the final answer clearly. If it is an error, fault or problem, give a troubleshooting plan: " +
            "most likely causes first, then numbered steps with what to check after each step.",
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
class ClaudeTutor(apiKey: String) {
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
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16_000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.HIGH).build())
            // If a safety classifier declines, the API retries on a suitable fallback model.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .messages(history.toList())
            .build()

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
        return if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) Outcome.REFUSED else Outcome.DONE
    }

    enum class Outcome { DONE, REFUSED }

    companion object {
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM_PROMPT = """
            You are ScreenLens, a tutor and troubleshooting assistant. The user pointed a phone camera at a
            screen, a book page or handwritten notes and took a photo. Your answer is shown on a second phone.

            - Read the image carefully. If part of it is unreadable (glare, blur, cut off), say which part and
              how to retake the photo (angle, distance, light), then answer as far as the readable part allows.
            - Answer in the language the user writes in. If they only chose a mode, use the language of the
              content in the image.
            - Format for a phone screen: short paragraphs, "##" headings, bullet or numbered lists, **bold**
              for key terms, code in ``` blocks. No tables.
            - For troubleshooting, flag safety risks (electricity, gas, heat, data loss) before the steps.
        """.trimIndent()
    }
}
