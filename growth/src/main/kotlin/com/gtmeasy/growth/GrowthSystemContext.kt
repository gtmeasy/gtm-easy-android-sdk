package com.gtmeasy.growth

import android.os.Build
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Snapshot of the device language / region / timezone settings.
 *
 * First-class ingest fields use BCP-47 [locale] + IANA [timezone]. A denser copy
 * is mirrored under `properties._ctx` for dashboard JSONExtract.
 */
data class GrowthSystemContext(
    val locale: String,
    val timezone: String,
    val region: String? = null,
    val language: String? = null,
    val utcOffsetMinutes: Int,
    val preferredLanguages: List<String> = emptyList(),
    val calendar: String? = null,
    val measurementSystem: String? = null,
) {
    fun asContextProperties(): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>(
            "locale" to locale,
            "timezone" to timezone,
            "utc_offset_min" to utcOffsetMinutes,
        )
        region?.let { out["region"] = it }
        language?.let { out["language"] = it }
        if (preferredLanguages.isNotEmpty()) out["preferred_languages"] = preferredLanguages
        calendar?.let { out["calendar"] = it }
        measurementSystem?.let { out["measurement_system"] = it }
        return out
    }

    companion object {
        fun capture(
            locale: Locale = Locale.getDefault(),
            timeZone: TimeZone = TimeZone.getDefault(),
        ): GrowthSystemContext {
            val offsetMin = TimeUnit.MILLISECONDS.toMinutes(timeZone.getOffset(System.currentTimeMillis()).toLong()).toInt()
            return GrowthSystemContext(
                locale = locale.toLanguageTag(),
                timezone = timeZone.id,
                region = locale.country.takeIf { it.isNotBlank() },
                language = locale.language.takeIf { it.isNotBlank() },
                utcOffsetMinutes = offsetMin,
                preferredLanguages = preferredLanguageTags().take(5),
                // Android Locale has no first-class calendar/measurement API matching iOS;
                // omit rather than invent values.
            )
        }

        private fun preferredLanguageTags(): List<String> {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val list = android.os.LocaleList.getDefault()
                    (0 until list.size()).map { list[it].toLanguageTag() }
                } else {
                    listOf(Locale.getDefault().toLanguageTag())
                }
            } catch (_: Throwable) {
                listOf(Locale.getDefault().toLanguageTag())
            }
        }
    }
}

/** Injectable seam so unit tests don't depend on the host machine's locale/tz. */
fun interface GrowthSystemContextProvider {
    fun current(): GrowthSystemContext
}

object LiveGrowthSystemContextProvider : GrowthSystemContextProvider {
    override fun current(): GrowthSystemContext = GrowthSystemContext.capture()
}

class FixedGrowthSystemContextProvider(private val value: GrowthSystemContext) : GrowthSystemContextProvider {
    override fun current(): GrowthSystemContext = value
}
