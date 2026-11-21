package com.lalilu.lmedia.lyric

import com.lalilu.lmedia.lyric.parser.SplParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplParserTest {
    @Test
    fun parsesTranslationsAndRepeatTimestamps() {
        val result = SplParser.parse(
            """
            [00:10.00][00:30.00]Hello
            [00:10.00]你好
            [00:30.00]再次你好
            """.trimIndent()
        )
        assertEquals(2, result.size)
        assertEquals("Hello", (result[0] as LyricItem.NormalLyric).content)
        assertEquals("你好", (result[0] as LyricItem.NormalLyric).translation)
        assertEquals(10_000L, result[0].time)
        assertEquals(30_000L, result[1].time)
    }

    @Test
    fun parsesUntimedTranslation() {
        val result = SplParser.parse(
            """
            [00:10.00]Hello
            Untimed translation
            [00:12.00]World
            """.trimIndent()
        )
        assertEquals(2, result.size)
        assertEquals("Untimed translation", (result[0] as LyricItem.NormalLyric).translation)
    }

    @Test
    fun parsesWordTimestampsWithBracketsAndAngles() {
        val result = SplParser.parse("[00:10.00]你好[00:13.00]椒盐音乐[00:14.00]")
        val words = result.single() as LyricItem.WordsLyric
        assertEquals(2, words.words.size)
        assertEquals("你好", words.words[0].content)
        assertEquals(10_000L, words.words[0].startTime)
        assertEquals(13_000L, words.words[0].endTime)
        assertEquals("椒盐音乐", words.words[1].content)
        assertEquals(13_000L, words.words[1].startTime)
        assertEquals(14_000L, words.words[1].endTime)
        assertEquals(14_000L, words.endTime)
    }

    @Test
    fun delayedFirstWordUsesAngleMarker() {
        val result = SplParser.parse("[00:10.00]<00:11.00>你好[00:13.00]椒盐音乐[00:14.00]")
        val words = (result.single() as LyricItem.WordsLyric).words
        assertEquals(11_000L, words[0].startTime)
        assertEquals(13_000L, words[0].endTime)
    }

    @Test
    fun ignoresAwlrcCompatibilityField() {
        val result = SplParser.parse(
            """
            [00:10.00]Hello
            [awlrc:lrc:SGVsbG8=,awlrc:V29ybGQ=]
            Translation
            """.trimIndent()
        )
        val normal = result.single() as LyricItem.NormalLyric
        assertEquals("Hello", normal.content)
        assertEquals("Translation", normal.translation)
    }

    @Test
    fun stripsTrailingWordTimestampFromDisplayText() {
        val result = SplParser.parse("[00:10.00]Hello[00:12.00]")
        assertEquals("Hello", (result.single() as LyricItem.NormalLyric).content)
    }

    @Test
    fun acceptsMinutesAndLongFractions() {
        val result = SplParser.parse("[103:3.405]Long song\n[104:3.5]Next")
        assertEquals(103 * 60_000L + 3_000L + 405L, result[0].time)
        assertEquals(104 * 60_000L + 3_500L, result[1].time)
        assertTrue((result[0] as LyricItem.NormalLyric).content == "Long song")
    }
}
