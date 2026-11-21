package com.lalilu.lmedia.lyric.parser

import com.lalilu.lmedia.lyric.LyricItem
import com.lalilu.lmedia.lyric.LyricParser
import java.util.regex.Pattern

/**
 * Parser for SPL (Salt Player Lyrics) and enhanced LRC.
 *
 * Supported features:
 *  - multiple timestamps per line (repeat shorthand)
 *  - explicit line end markers (timestamp-only lines)
 *  - translations sharing a timestamp or written on the following untimed line
 *  - per-word/per-phrase timestamps using `[mm:ss.xxx]` or `<mm:ss.xxx>`
 *  - 1..6 fractional digits and 1..3 minute digits
 *  - LX Music `[awlrc:...]` compatibility fields are ignored
 */
object SplParser : LyricParser {
    private val TIMESTAMP: Pattern = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})\\.(\\d{1,6})]")
    private val LEADING_TIMESTAMPS: Pattern =
        Pattern.compile("^\\s*((?:\\[\\d{1,3}:\\d{1,2}\\.\\d{1,6}])+)")
    private val WORD_TIMESTAMP: Pattern =
        Pattern.compile("[\\[<](\\d{1,3}):(\\d{1,2})\\.(\\d{1,6})[\\]>]")
    private val AWLRC_FIELD: Pattern =
        Pattern.compile("^\\s*\\[awlrc:.*]\\s*$", Pattern.CASE_INSENSITIVE)
    private val METADATA_FIELD: Pattern =
        Pattern.compile("^\\s*\\[[A-Za-z][A-Za-z0-9_-]*:[^]]*]\\s*$")
    private val PROPERTY_LINE: Pattern =
        Pattern.compile("^\\s*[A-Za-z][A-Za-z0-9_]*\\s*=\\s*\".*\"\\s*;?\\s*$")

    private data class RawLine(
        val startTime: Long,
        val text: String,
        val order: Int,
    )

    override fun parse(lyric: String): List<LyricItem> {
        if (lyric.isBlank()) return emptyList()
        var source = lyric.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        // Media Monkey style: Lyrics="[00:01.00]...";
        if (source.contains("Lyrics=\"")) {
            source = source.substringAfter("Lyrics=\"")
                .substringBeforeLast("\";")
                .replace("\\n", "\n")
        }

        val rawLines = mutableListOf<RawLine>()
        val explicitEnds = mutableListOf<Long>()
        val untimedTranslations = mutableMapOf<Long, MutableList<String>>()
        var lastTimes: List<Long> = emptyList()
        var order = 0

        for (line in source.split('\n')) {
            if (line.isBlank()) continue
            val trimmed = line.trim()
            if (AWLRC_FIELD.matcher(trimmed).matches()) continue
            if (METADATA_FIELD.matcher(trimmed).matches()) continue
            if (PROPERTY_LINE.matcher(trimmed).matches()) continue

            val leadingMatcher = LEADING_TIMESTAMPS.matcher(trimmed)
            if (leadingMatcher.find()) {
                val leading = leadingMatcher.group(1) ?: continue
                val text = trimmed.substring(leadingMatcher.end()).trim()
                val times = mutableListOf<Long>()
                val timeMatcher = TIMESTAMP.matcher(leading)
                while (timeMatcher.find()) {
                    times += parseTime(
                        timeMatcher.group(1)!!,
                        timeMatcher.group(2)!!,
                        timeMatcher.group(3)!!
                    )
                }
                if (times.isEmpty()) continue
                if (text.isEmpty()) {
                    explicitEnds += times
                    lastTimes = emptyList()
                    continue
                }
                for (time in times) {
                    rawLines += RawLine(time, text, order++)
                }
                lastTimes = times
            } else {
                // Untimed text immediately following a timestamped line is a translation.
                if (lastTimes.isNotEmpty()) {
                    val targets = if (lastTimes.size == 1) lastTimes else listOf(lastTimes.first(), lastTimes.last()).distinct()
                    for (time in targets) {
                        untimedTranslations.getOrPut(time) { mutableListOf() } += trimmed
                    }
                }
            }
        }

        if (rawLines.isEmpty()) return emptyList()

        // Group by start time: first line is the main lyric, following lines are translations.
        val grouped = linkedMapOf<Long, MutableList<RawLine>>()
        for (raw in rawLines) {
            grouped.getOrPut(raw.startTime) { mutableListOf() } += raw
        }
        val ordered = grouped.keys.sorted()
        val result = mutableListOf<LyricItem>()

        ordered.forEachIndexed { index, start ->
            val group = grouped[start] ?: return@forEachIndexed
            val main = group.firstOrNull() ?: return@forEachIndexed
            val translations = buildList {
                group.drop(1).forEach { if (it.text.isNotBlank()) add(it.text) }
                untimedTranslations[start]?.forEach { if (it.isNotBlank()) add(it) }
            }.distinct()

            val nextStart = ordered.getOrNull(index + 1)
            val naturalEnd = nextStart
                ?: explicitEnds.filter { it >= start }.minOrNull()
                ?: (start + 5_000L)
            val explicitEnd = explicitEnds
                .filter { it >= start && (nextStart == null || it <= nextStart) }
                .minOrNull()
            val end = explicitEnd ?: naturalEnd

            result += buildItem(main.text, start, end, translations, index)
        }

        return result
    }

    private fun buildItem(
        text: String,
        start: Long,
        end: Long,
        translations: List<String>,
        index: Int,
    ): LyricItem {
        val key = "${index}_$start"
        val words = parseWords(text, start, end)
        val displayText = if (words.isNotEmpty()) {
            words.joinToString(separator = "") { it.content }
        } else {
            stripWordTimestamps(text)
        }
        if (displayText.isBlank()) {
            return LyricItem.NormalLyric(content = "", translation = null, time = start, key = key)
        }
        return if (words.size > 1) {
            LyricItem.WordsLyric(
                key = key,
                agent = "",
                words = words,
                translation = translations.map {
                    LyricItem.WordsLyric.Translation(content = it, lang = "und")
                },
                startTime = start,
                endTime = words.lastOrNull()?.endTime?.coerceAtLeast(start) ?: end,
            )
        } else {
            LyricItem.NormalLyric(
                content = displayText,
                translation = translations.firstOrNull(),
                time = start,
                key = key,
            )
        }
    }

    private fun parseWords(text: String, lineStart: Long, lineEnd: Long): List<LyricItem.WordsLyric.WordWithTiming> {
        val matcher = WORD_TIMESTAMP.matcher(text)
        if (!matcher.find()) return emptyList()

        val words = mutableListOf<LyricItem.WordsLyric.WordWithTiming>()
        var cursor = 0
        var segmentStart = lineStart

        fun addSegment(content: String, from: Long, to: Long) {
            val trimmed = content.trim()
            if (trimmed.isEmpty()) return
            if (to <= from) return
            words += LyricItem.WordsLyric.WordWithTiming(
                content = trimmed,
                startTime = from,
                endTime = to,
            )
        }

        do {
            val markerTime = parseTime(
                matcher.group(1)!!,
                matcher.group(2)!!,
                matcher.group(3)!!
            )
            val segment = text.substring(cursor, matcher.start())
            if (segment.isNotEmpty()) {
                addSegment(segment, segmentStart, markerTime.coerceAtLeast(segmentStart))
            }
            segmentStart = markerTime
            cursor = matcher.end()
        } while (matcher.find())

        val tail = text.substring(cursor)
        if (tail.isNotEmpty()) {
            addSegment(tail, segmentStart, lineEnd.coerceAtLeast(segmentStart + 1))
        }

        // Word timestamps must be strictly increasing; drop invalid leftovers.
        var lastStart = Long.MIN_VALUE
        return words.filter {
            if (it.startTime < lastStart || it.content.isBlank()) {
                false
            } else {
                lastStart = it.startTime
                true
            }
        }
    }

    private fun stripWordTimestamps(text: String): String {
        return WORD_TIMESTAMP.matcher(text).replaceAll("").trim()
    }

    private fun parseTime(minutes: String, seconds: String, fraction: String): Long {
        val minute = minutes.toLongOrNull() ?: 0
        val second = seconds.toLongOrNull() ?: 0
        val millis = when {
            fraction.length >= 3 -> fraction.substring(0, 3).toLong()
            else -> fraction.padEnd(3, '0').toLong()
        }
        return (minute * 60 + second) * 1000 + millis
    }
}
