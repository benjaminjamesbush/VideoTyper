package com.videotyper.game

import kotlin.random.Random

/**
 * Picks the word the child will type from a subtitle line.
 *
 * A "word" is a whole run of letters, *including* any word-internal apostrophe / hyphen / digit and
 * any accented letters — so "couldn't", "well-known", "H2O" and "café" are each captured as ONE
 * token and never chopped into fragments. Surrounding whitespace and punctuation (parens, colon,
 * comma, quotes…) delimit words, so a sound cue "(LAUGHS)" and a speaker label "ELMO:" still yield
 * "LAUGHS" / "ELMO".
 *
 * A word is eligible only if it is ENTIRELY plain A-Z letters, so anything with an *internal*
 * apostrophe / accent / hyphen / digit is dropped as a whole rather than reduced to a fragment. A
 * leading/trailing apostrophe is stripped first, though, so a possessive plural "cats'" becomes the
 * complete, typeable word "cats" (whereas "couldn't" keeps its internal apostrophe and stays out).
 * 1-2 letter words are avoided (preferred, not hard-excluded — see the fallback).
 */
object WordSelector {
    // A whole word: any run of letters / digits / apostrophes / hyphens, kept together so it's never
    // split. Only surrounding whitespace and punctuation delimit words.
    private val WORD_TOKEN = Regex("[\\p{L}\\p{Nd}'’-]+")
    // Eligible only if the (edge-apostrophe-trimmed) word is entirely plain ASCII letters.
    private val ALL_ASCII = Regex("[a-zA-Z]+")

    /** Drop a possessive / leading apostrophe at the word edge ("cats'" -> "cats"); keep internal ones. */
    private fun core(token: String): String = token.trim { it == '\'' || it == '’' }

    private fun eligibleWords(text: String): List<String> =
        WORD_TOKEN.findAll(text).map { core(it.value) }.filter { it.matches(ALL_ASCII) }.toList()

    /** True if the line has at least one all-letters word to type. */
    fun hasTypeableWords(text: String): Boolean =
        WORD_TOKEN.findAll(text).any { core(it.value).matches(ALL_ASCII) }

    fun selectWord(text: String, random: Random = Random.Default): String? {
        val words = eligibleWords(text)
        if (words.isEmpty()) return null
        val longer = words.filter { it.length > 2 }
        return longer.ifEmpty { words }.random(random)
    }
}
