package io.github.eggplants.maydo.core

/** Finding the link in what another app shares: a bare URL, or a title or quote around one. */
object SharedText {
    // Up to the first space, or anything outside ASCII: a Japanese title often runs straight
    // on after the link, as in "https://…）より" or "https://…、".
    private val URL = Regex("""https?://[\x21-\x7E]+""")

    /** The first link in [text], without what ends the sentence around it; null when none. */
    fun url(text: String?): String? = text?.let(URL::find)?.value?.trimEnd('.', ',', '"', '\'', '>')
}
