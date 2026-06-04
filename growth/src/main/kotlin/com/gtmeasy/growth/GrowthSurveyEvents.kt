package com.gtmeasy.growth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Flexible onboarding-survey capture. Mirrors the PostHog Surveys model: a
 * submission is a list of self-describing answers, each carrying its question
 * type + optional human label, so the GTM Easy dashboard aggregates without a
 * server-side survey definition. Submit with [GrowthAnalytics.submitSurvey];
 * build answers with the [SurveyAnswers] factories. The `trackSurvey*` lifecycle
 * helpers power shown→completed completion rate. See
 * docs/plans/2026-06-03-onboarding-survey-capture.md.
 */

/** Submission outcome. `completed`/`dismissed` emit a lifecycle event; `partial` does not. */
enum class SurveyStatus(val wire: String) {
    COMPLETED("completed"),
    PARTIAL("partial"),
    DISMISSED("dismissed"),
}

/**
 * One answered question. Serialized with the SDK's `explicitNulls = false` Json,
 * so null fields are omitted from the wire payload. Build via [SurveyAnswers].
 */
@Serializable
data class SurveyAnswer(
    val questionId: String,
    val type: String,
    val questionText: String? = null,
    val position: Int? = null,
    val choices: List<String>? = null,
    val choiceLabels: List<String>? = null,
    val number: Double? = null,
    val text: String? = null,
    val bool: Boolean? = null,
    val skipped: Boolean? = null,
    /**
     * Optional per-answer extensibility payload (answer timing, validation
     * flags…). Merged OVER the submission-level metadata server-side; persisted
     * to the `metadata` JSON column. Build via the [SurveyAnswers] factories,
     * which accept a plain `Map<String, Any?>` and convert it for you.
     */
    val metadata: JsonObject? = null,
)

/** Server acknowledgement for a survey submission. */
data class SurveySubmitResponse(
    /** Idempotency key — the one you supplied or a server-generated UUID. */
    val submissionId: String,
    /** Number of answer rows persisted. */
    val accepted: Int,
    val warnings: List<String>,
)

/** Typed builders so `type` always matches the payload shape. */
object SurveyAnswers {
    /** Single-choice answer. [label] is the human-readable option text (optional). */
    fun singleChoice(questionId: String, choice: String, label: String? = null, questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, "single_choice", questionText, choices = listOf(choice), choiceLabels = label?.let { listOf(it) }, metadata = metadata?.toJson())

    /** Multi-choice answer. [labels] (if given) must be parallel to [choices]. */
    fun multiChoice(questionId: String, choices: List<String>, labels: List<String>? = null, questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, "multi_choice", questionText, choices = choices, choiceLabels = labels, metadata = metadata?.toJson())

    /** Star / 1–5 style rating. */
    fun rating(questionId: String, value: Int, questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, "rating", questionText, number = value.toDouble(), metadata = metadata?.toJson())

    /** 0–10 Net Promoter Score answer. */
    fun nps(questionId: String, value: Int, questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, "nps", questionText, number = value.toDouble(), metadata = metadata?.toJson())

    /** Generic numeric scale. */
    fun scale(questionId: String, value: Double, questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, "scale", questionText, number = value, metadata = metadata?.toJson())

    /** Yes/no answer. */
    fun boolean(questionId: String, value: Boolean, questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, "boolean", questionText, bool = value, metadata = metadata?.toJson())

    /** Free-text answer (up to 2 000 chars server-side). */
    fun text(questionId: String, value: String, questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, "text", questionText, text = value, metadata = metadata?.toJson())

    /** Explicitly-skipped question (recorded so completion math can exclude it). */
    fun skip(questionId: String, type: String = "text", questionText: String? = null, metadata: Map<String, Any?>? = null): SurveyAnswer =
        SurveyAnswer(questionId, type, questionText, skipped = true, metadata = metadata?.toJson())
}

/** Lifecycle: the survey was shown. Powers shown→completed completion rate. */
suspend fun GrowthAnalytics.trackSurveyShown(
    surveyId: String,
    surveyName: String? = null,
    surveyVersion: String? = null,
): IngestResponse = track("survey.shown", surveyProperties(surveyId, surveyName, surveyVersion))

/** Lifecycle: the user began answering the survey. */
suspend fun GrowthAnalytics.trackSurveyStarted(
    surveyId: String,
    surveyName: String? = null,
    surveyVersion: String? = null,
): IngestResponse = track("survey.started", surveyProperties(surveyId, surveyName, surveyVersion))

private fun surveyProperties(surveyId: String, surveyName: String?, surveyVersion: String?): Map<String, Any?> =
    buildMap {
        put("survey_id", surveyId)
        surveyName?.let { put("survey_name", it) }
        surveyVersion?.let { put("survey_version", it) }
    }
