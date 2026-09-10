package com.patgrady64.picroulette

/**
 * Reshuffles a completed roulette deck while avoiding the most noticeable
 * boundary duplicate: the last photo of the old deck immediately becoming
 * the first photo of the new deck.
 *
 * During a deck, the caller advances by index and never chooses a random item,
 * so every entry is shown exactly once before this function is called again.
 */
fun <T> reshuffleDeckAvoidingBoundaryRepeat(deck: MutableList<T>) {
    if (deck.size < 2) return

    val previousLast = deck.last()
    deck.shuffle()

    if (deck.first() == previousLast) {
        val swapIndex = (1 until deck.size).firstOrNull { deck[it] != previousLast }
        if (swapIndex != null) {
            val first = deck[0]
            deck[0] = deck[swapIndex]
            deck[swapIndex] = first
        }
    }
}
