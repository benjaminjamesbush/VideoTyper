package com.videotyper.game

import kotlin.random.Random

/**
 * Picks the word the child will type from a subtitle line. Port of the selection rules in the
 * desktop video_player.py: skip parenthetical sound cues, speaker labels ("ELMO:"), contraction
 * fragments (words touching an apostrophe), and prefer words longer than 2 letters.
 */
object WordSelector {
    private val WORD_REGEX = Regex("[a-zA-Z]+")
    private val PAREN_REGEX = Regex("\\([^)]*\\)")

    /** True if anything typeable remains once parenthetical sound cues like "(GASPS)" are removed. */
    fun hasTypeableWords(text: String): Boolean =
        WORD_REGEX.containsMatchIn(PAREN_REGEX.replace(text, ""))

    fun selectWord(text: String, random: Random = Random.Default): String? {
        val matches = WORD_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return null

        // Words directly followed by a colon are speaker labels, not dialogue.
        val noSpeakers = matches.filterNot { m ->
            text.substring(m.range.last + 1).take(5).contains(':')
        }
        // Words touching an apostrophe are contraction fragments ("couldn't" -> "couldn", "t").
        val noContractions = noSpeakers.filterNot { m ->
            val before = text.getOrNull(m.range.first - 1)
            val after = text.getOrNull(m.range.last + 1)
            before == '\'' || after == '\'' || before == '’' || after == '’'
        }
        val longerWords = noContractions.filter { it.value.length > 2 }

        val pool = longerWords.ifEmpty { noContractions }.ifEmpty { noSpeakers }.ifEmpty { matches }
        return pool.random(random).value
    }
}
