package com.videotyper.game

import kotlin.random.Random

/**
 * Picks the word the child will type from a subtitle line.
 *
 * A "word" is a whole run of letters, *including* any word-internal apostrophe / hyphen and any
 * accented letters — so "couldn't", "well-known" and "café" are each captured as ONE token and are
 * never chopped into fragments. Punctuation and whitespace *around* a word are delimiters, so a
 * sound cue "(LAUGHS)" and a speaker label "ELMO:" still yield the words "LAUGHS" / "ELMO".
 *
 * A word is eligible only if it is ENTIRELY plain A-Z letters (all the child can type). So any word
 * containing an apostrophe, hyphen, accent, digit, etc. is dropped as a whole rather than split into
 * a fragment. Sound cues and speaker names are eligible; 1-2 letter words are avoided (preferred,
 * not hard-excluded — see the fallback).
 */
object WordSelector {
    // A whole word: letters, keeping internal ' ' - between letters together (so we never split).
    private val WORD_TOKEN = Regex("\\p{L}+(?:['’-]\\p{L}+)*")
    // Eligible only if the entire word is plain ASCII letters.
    private val ALL_ASCII = Regex("[a-zA-Z]+")

    private fun eligibleWords(text: String): List<String> =
        WORD_TOKEN.findAll(text).map { it.value }.filter { it.matches(ALL_ASCII) }.toList()

    /** True if the line has at least one all-letters word to type. */
    fun hasTypeableWords(text: String): Boolean =
        WORD_TOKEN.findAll(text).any { it.value.matches(ALL_ASCII) }

    fun selectWord(text: String, random: Random = Random.Default): String? {
        val words = eligibleWords(text)
        if (words.isEmpty()) return null
        val longer = words.filter { it.length > 2 }
        return longer.ifEmpty { words }.random(random)
    }
}
