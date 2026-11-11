package dev.foldman.deck

/** The case the player wears. Chosen on the tape shelf and remembered. */
enum class Skin(val label: String) {
    GUNMETAL("GUNMETAL"),
    SPORT("SPORT"),
    ;

    fun next(): Skin = entries[(ordinal + 1) % entries.size]
}
