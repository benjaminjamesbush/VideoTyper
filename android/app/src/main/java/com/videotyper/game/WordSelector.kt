package com.videotyper.game

import kotlin.random.Random

/**
 * Picks the word the child will type from a subtitle line.
 *
 * Eligible: ordinary dialogue, **sound cues** ("(LAUGHS)"), and **speaker names** ("ELMO:").
 * Skipped: contraction / possessive fragments — because the regex is letters-only, "couldn't"
 * splits into "couldn" + "t", and we don't teach the apostrophe key, so any run touching a "'" is
 * dropped. Words of 1-2 letters are avoided (preferred, not hard-excluded — see the fallback).
 */
object WordSelector {
    private val WORD_REGEX = Regex("[a-zA-Z]+")

    /** True if the line contains any letters at all (i.e. something to type). */
    fun hasTypeableWords(text: String): Boolean = WORD_REGEX.containsMatchIn(text)

    fun selectWord(text: String, random: Random = Random.Default): String? {
        val matches = WORD_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return null

        // Words touching an apostrophe are contraction/possessive fragments ("couldn't" -> "couldn",
        // "t"; "Elmo's" -> "Elmo", "s"). Skip them since we don't teach the apostrophe key.
        val noContractions = matches.filterNot { m ->
            val before = text.getOrNull(m.range.first - 1)
            val after = text.getOrNull(m.range.last + 1)
            before == '\'' || after == '\'' || before == '’' || after == '’'
        }
        val longerWords = noContractions.filter { it.value.length > 2 }

        val pool = longerWords.ifEmpty { noContractions }.ifEmpty { matches }
        return pool.random(random).value
    }
}
