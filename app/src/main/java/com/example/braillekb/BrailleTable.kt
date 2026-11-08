package com.example.braillekb

/**
 * Braille pt-BR grau 1 (Grafia Braille para a Língua Portuguesa).
 * Cada célula é uma máscara de bits: ponto N = bit (N-1).
 */
object BrailleTable {
    private fun dots(vararg d: Int): Int = d.fold(0) { acc, n -> acc or (1 shl (n - 1)) }

    val CAPITAL_SIGN = dots(4, 6)          // sinal de maiúscula (duas vezes = palavra toda)
    val NUMBER_SIGN = dots(3, 4, 5, 6)
    val DOT_6 = dots(6)                    // prefixo de sinais de duas células: "/", ")" e "]"
    val DOT_3 = dots(3)

    val letters: Map<Int, Char> = mapOf(
        dots(1) to 'a', dots(1, 2) to 'b', dots(1, 4) to 'c', dots(1, 4, 5) to 'd',
        dots(1, 5) to 'e', dots(1, 2, 4) to 'f', dots(1, 2, 4, 5) to 'g', dots(1, 2, 5) to 'h',
        dots(2, 4) to 'i', dots(2, 4, 5) to 'j', dots(1, 3) to 'k', dots(1, 2, 3) to 'l',
        dots(1, 3, 4) to 'm', dots(1, 3, 4, 5) to 'n', dots(1, 3, 5) to 'o',
        dots(1, 2, 3, 4) to 'p', dots(1, 2, 3, 4, 5) to 'q', dots(1, 2, 3, 5) to 'r',
        dots(2, 3, 4) to 's', dots(2, 3, 4, 5) to 't', dots(1, 3, 6) to 'u',
        dots(1, 2, 3, 6) to 'v', dots(2, 4, 5, 6) to 'w', dots(1, 3, 4, 6) to 'x',
        dots(1, 3, 4, 5, 6) to 'y', dots(1, 3, 5, 6) to 'z',
        // acentuadas
        dots(1, 2, 3, 4, 6) to 'ç', dots(1, 2, 3, 5, 6) to 'á', dots(1, 2, 4, 6) to 'à',
        dots(1, 6) to 'â', dots(3, 4, 5) to 'ã', dots(1, 2, 3, 4, 5, 6) to 'é',
        dots(1, 2, 6) to 'ê', dots(3, 4) to 'í', dots(3, 4, 6) to 'ó',
        dots(1, 4, 5, 6) to 'ô', dots(2, 4, 6) to 'õ', dots(2, 3, 4, 5, 6) to 'ú',
        dots(1, 2, 5, 6) to 'ü',
    )

    /** Sinais de uma célula. Reticências = 3 três vezes e travessão = 36 duas vezes: saem digitando em sequência. */
    val punctuation: Map<Int, Char> = mapOf(
        dots(2) to ',', dots(2, 3) to ';', dots(2, 5) to ':', dots(3) to '.',
        dots(2, 6) to '?', dots(2, 3, 5) to '!', dots(2, 3, 6) to '"',
        dots(3, 6) to '-', dots(3, 5) to '*',
        dots(1, 5, 6) to '@',   // arroba: não confirmado no PDF oficial, ver README/conversa
    )

    /** Dentro de números, estas células são parênteses e colchetes numéricos. */
    val numericBrackets: Map<Int, Char> = mapOf(
        dots(1, 2, 6) to '(', dots(3, 4, 5) to ')',
        dots(1, 2, 3, 5, 6) to '[', dots(2, 3, 4, 5, 6) to ']',
    )

    /** Depois do ponto 6: 6+2 = "/", 6+345 = ")" literal, 6+23456 = "]" literal. */
    val afterDot6: Map<Int, Char> = mapOf(
        dots(2) to '/', dots(3, 4, 5) to ')', dots(2, 3, 4, 5, 6) to ']',
    )

    /** Abertura literal: ê (126) seguido de ponto 3 = "(", á (12356) seguido de ponto 3 = "[". */
    val openingBeforeDot3: Map<Char, Char> = mapOf('ê' to '(', 'á' to '[')

    /** Letras a-j viram dígitos 1-9,0 depois do sinal de número. */
    fun digitFor(letter: Char): Char? = when (letter) {
        in 'a'..'i' -> '1' + (letter - 'a')
        'j' -> '0'
        else -> null
    }
}
