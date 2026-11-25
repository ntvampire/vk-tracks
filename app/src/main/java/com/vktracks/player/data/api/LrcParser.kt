package com.vktracks.player.data.api

import com.vktracks.player.data.model.LyricLine
import com.vktracks.player.data.model.Lyrics
import kotlin.math.roundToLong

/**
 * Парсер текстов песен в стандартном формате LRC ([mm:ss.xx] текст).
 * Поддерживает множественные таймкоды на одну строку и конвертирует их в непрерывные интервалы LyricLine.
 */
object LrcParser {
    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2}(?:\.\d+)?)\]""")

    fun buildLyrics(plainText: String?, syncedLyrics: String?, isInstrumental: Boolean): Lyrics {
        if (isInstrumental) {
            return Lyrics(plainText = emptyList(), lines = emptyList(), isInstrumental = true)
        }
        val syncedLines = parse(syncedLyrics)
        val plainList = plainText?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return Lyrics(
            plainText = plainList,
            lines = syncedLines,
            isInstrumental = false
        )
    }

    fun parse(lrcContent: String?): List<LyricLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val rawLines = lrcContent.lines()
        val parsedList = mutableListOf<Pair<Long, String>>()

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Игнорируем служебные теги метаданных ID3/LRC ([ar:...], [ti:...], [length:...])
            if (trimmed.startsWith("[ar:", ignoreCase = true) ||
                trimmed.startsWith("[ti:", ignoreCase = true) ||
                trimmed.startsWith("[al:", ignoreCase = true) ||
                trimmed.startsWith("[by:", ignoreCase = true) ||
                trimmed.startsWith("[length:", ignoreCase = true) ||
                trimmed.startsWith("[re:", ignoreCase = true) ||
                trimmed.startsWith("[ve:", ignoreCase = true)
            ) {
                continue
            }

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            // Текст строки идет после последнего таймкода в строке
            val lastMatch = matches.last()
            val text = trimmed.substring(lastMatch.range.last + 1).trim()

            // Если в одной строке несколько таймкодов (повтор припева [00:10.00][00:20.00]...), добавляем каждый
            for (match in matches) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toDoubleOrNull() ?: 0.0
                val beginMs = min * 60_000L + (sec * 1000.0).roundToLong()
                parsedList.add(beginMs to text)
            }
        }

        if (parsedList.isEmpty()) return emptyList()

        parsedList.sortBy { it.first }

        val result = ArrayList<LyricLine>(parsedList.size)
        for (i in parsedList.indices) {
            val (beginMs, text) = parsedList[i]
            val endMs = if (i + 1 < parsedList.size) {
                parsedList[i + 1].first
            } else {
                beginMs + 5000L
            }
            result.add(LyricLine(beginMs = beginMs, endMs = endMs, text = text))
        }

        return result
    }
}
