package com.gtmeasy.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthSystemContextTest {
    @Test
    fun captureIsNonEmpty() {
        val snap = GrowthSystemContext.capture()
        assertTrue(snap.locale.isNotBlank())
        assertTrue(snap.timezone.isNotBlank())
        assertFalse(snap.locale.contains("_"))
    }

    @Test
    fun asContextPropertiesIncludesCoreFields() {
        val snap = GrowthSystemContext(
            locale = "zh-Hans-CN",
            timezone = "Asia/Shanghai",
            region = "CN",
            language = "zh",
            utcOffsetMinutes = 480,
            preferredLanguages = listOf("zh-Hans-CN", "en-US"),
        )
        val props = snap.asContextProperties()
        assertEquals("zh-Hans-CN", props["locale"])
        assertEquals("Asia/Shanghai", props["timezone"])
        assertEquals("CN", props["region"])
        assertEquals("zh", props["language"])
        assertEquals(480, props["utc_offset_min"])
        assertEquals(listOf("zh-Hans-CN", "en-US"), props["preferred_languages"])
    }

    @Test
    fun fixedProviderReturnsInjectedValue() {
        val fixed = GrowthSystemContext(
            locale = "de-DE",
            timezone = "Europe/Berlin",
            utcOffsetMinutes = 120,
        )
        val provider = FixedGrowthSystemContextProvider(fixed)
        assertEquals(fixed, provider.current())
    }
}
