package com.example.braillekb

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Fala com o motor de voz do sistema. Cada fala interrompe a anterior (QUEUE_FLUSH), então
 * digitar rápido nunca deixa a fala atrasada: sempre soa o último caractere.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    private val names = mapOf(
        '.' to "ponto", ',' to "vírgula", ';' to "ponto e vírgula", ':' to "dois pontos",
        '?' to "interrogação", '!' to "exclamação", '"' to "aspas", '-' to "hífen",
        '*' to "asterisco", '@' to "arroba", '(' to "abre parênteses", ')' to "fecha parênteses",
        '[' to "abre colchetes", ']' to "fecha colchetes", '/' to "barra", ' ' to "espaço",
    )

    /** Nome falado de um caractere (sinais por extenso, letras e números como são). */
    fun nameOf(c: Char): String = names[c] ?: c.toString()

    /** Com leitor de tela ligado ele já fala o que é digitado: o motor próprio fica calado (salvo force). */
    fun speak(context: Context, text: String, force: Boolean = false) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        if (!force && am.isEnabled && am.isTouchExplorationEnabled) return
        val engine = tts
        if (engine == null) {
            pending = text
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.language = Locale("pt", "BR")
                    pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "ps") }
                    pending = null
                }
            }
        } else if (ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ps")
        } else {
            pending = text
        }
    }
}
