package com.example.braillekb

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout

class BrailleKeyboardService : InputMethodService() {

    private var capitalNext = false
    private var numberMode = false
    private var view: BrailleView? = null
    private var container: FrameLayout? = null
    private var overlayView: BrailleView? = null

    override fun onCreate() {
        super.onCreate()
        KeySounds.init(this)
    }

    private fun click(key: Key = Key.STANDARD) = KeySounds.play(this, key)

    override fun onCreateInputView(): View {
        val c = FrameLayout(this)
        val h = (resources.displayMetrics.heightPixels * 0.6).toInt()
        c.minimumHeight = h   // garante a altura mesmo com o conteúdo escondido (modo overlay)
        c.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        val v = BrailleView(this, ::handleChord, ::handleGesture)
        view = v
        c.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container = c
        return c
    }

    private fun screenReaderOn(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        capitalNext = false
        numberMode = false
        val c = container ?: return
        val svc = PsAccessibilityService.instance
        if (screenReaderOn() && svc != null) {
            // Com leitor de tela: os toques vêm por uma janela de overlay de acessibilidade.
            view?.visibility = View.GONE
            overlayWanted = true
            val show = {
                // o teclado pode ter sido fechado enquanto esperávamos o layout
                if (overlayWanted) {
                    overlayView = svc.showKeyboard(::handleChord, ::handleGesture)
                }
            }
            if (c.isLaidOut && c.height > 0) {
                c.post(show)
            } else {
                // ainda não foi medido: espera o primeiro layout
                c.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View?, l: Int, t: Int, r: Int, b: Int,
                        ol: Int, ot: Int, or: Int, ob: Int,
                    ) {
                        if (b - t > 0) {
                            c.removeOnLayoutChangeListener(this)
                            show()
                        }
                    }
                })
            }
        } else {
            view?.visibility = View.VISIBLE
        }
    }

    private var overlayWanted = false

    private fun hideOverlay() {
        overlayWanted = false
        PsAccessibilityService.instance?.hideKeyboard()
        overlayView = null
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        hideOverlay()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        hideOverlay()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private var capsWord = false        // sinal de maiúscula duas vezes: palavra toda
    private var lastCellWasCapital = false
    private var pendingDot6 = false      // ponto 6 sozinho: prefixo de "/", ")" e "]"
    private var lastOut: Char? = null    // último caractere escrito pelo teclado (para "(" e "[")

    private fun say(text: String) = Speaker.speak(this, text)

    private fun emit(ic: android.view.inputmethod.InputConnection, out: Char, spoken: String = Speaker.nameOf(out)) {
        ic.commitText(out.toString(), 1)
        lastOut = out
        say(spoken)
        click()
    }

    private fun handleChord(mask: Int) {
        val ic = currentInputConnection ?: return

        // segunda célula de um sinal que começou com o ponto 6
        if (pendingDot6) {
            pendingDot6 = false
            BrailleTable.afterDot6[mask]?.let { emit(ic, it); return }
        } else if (mask == BrailleTable.DOT_6) {
            pendingDot6 = true
            click()
            return
        }

        // maiúscula: uma vez = próxima letra; duas vezes = até o espaço
        if (mask == BrailleTable.CAPITAL_SIGN) {
            if (lastCellWasCapital) { capsWord = true; say("maiúsculas") }
            else { capitalNext = true; say("maiúscula") }
            lastCellWasCapital = true
            click()
            return
        }
        lastCellWasCapital = false

        // sinal de número: liga; apertar de novo desliga
        if (mask == BrailleTable.NUMBER_SIGN) {
            numberMode = !numberMode
            say(if (numberMode) "número" else "letras")
            click()
            return
        }

        // "(" literal = ê + ponto 3, "[" literal = á + ponto 3
        if (mask == BrailleTable.DOT_3 && !numberMode) {
            BrailleTable.openingBeforeDot3[lastOut]?.let {
                ic.deleteSurroundingText(1, 0)
                emit(ic, it)
                return
            }
        }

        val letter = BrailleTable.letters[mask]
        if (numberMode) {
            val digit = letter?.let { BrailleTable.digitFor(it) }
            if (digit != null) { emit(ic, digit); return }
            BrailleTable.numericBrackets[mask]?.let { emit(ic, it); return }
            // vírgula e ponto continuam o número (3,5 e 1.000); outros sinais ou letras encerram
            val p = BrailleTable.punctuation[mask]
            if (p == ',' || p == '.') { emit(ic, p); return }
            numberMode = false
        }

        val out: Char? = when {
            letter != null -> if (capitalNext || capsWord) letter.uppercaseChar() else letter
            else -> BrailleTable.punctuation[mask]
        }
        if (out != null) {
            emit(ic, out)
            capitalNext = false
        }
    }

    private fun handleGesture(g: Gesture) {
        val ic = currentInputConnection ?: return
        // qualquer gesto interrompe um sinal de duas células pendente (ponto 6 isolado)
        // e o modo maiúscula de uma letra só; "palavra toda" (capsWord) só termina no espaço
        pendingDot6 = false
        capitalNext = false
        when (g) {
            Gesture.SPACE -> { speakWordBeforeSpace(ic); ic.commitText(" ", 1); numberMode = false; capsWord = false; lastOut = null; click(Key.SPACE) }
            Gesture.BACKSPACE -> { speakDeletedChar(ic); deleteOne(); lastOut = null; click(Key.DELETE) }
            Gesture.NEWLINE -> {
                click(Key.RETURN)
                // texto "\n" e não a tecla Enter: em apps como o WhatsApp a tecla Enter envia
                ic.commitText("\n", 1)
                lastOut = null
                say("nova linha")
            }
            Gesture.SEND -> {
                click(Key.RETURN)
                // ação do campo (enviar, buscar, ir...); se não houver, Enter normal
                if (!sendDefaultEditorAction(true)) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            Gesture.CLOSE -> {
                click(Key.DELETE)
                requestHideSelf(0)
            }
            Gesture.DELETE_WORD -> {
                click(Key.DELETE)
                val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
                val trimmed = before.trimEnd()
                val wordStart = trimmed.lastIndexOf(' ') + 1
                val word = before.substring(wordStart).trim { !it.isLetterOrDigit() }
                say(if (word.isEmpty()) "apagado" else "apagado $word")
                ic.deleteSurroundingText(before.length - wordStart, 0)
            }
            Gesture.SWITCH_KEYBOARD -> switchKeyboard()
            Gesture.OPEN_SETTINGS -> {
                click(Key.DELETE)
                say("configurações do teclado")
                startActivity(android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Gesture.NEXT_MODE -> { /* reservado */ }
        }
    }

    /** Fala a palavra que acabou de ser escrita (sem pontuação); sem palavra, fala "espaço". */
    private fun speakWordBeforeSpace(ic: android.view.inputmethod.InputConnection) {
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: ""
        val token = before.takeLastWhile { !it.isWhitespace() }
        val word = token.trim { !it.isLetterOrDigit() }
        say(if (word.isEmpty()) "espaço" else word)
    }

    /** Fala o caractere que vai ser apagado. */
    private fun speakDeletedChar(ic: android.view.inputmethod.InputConnection) {
        if (!ic.getSelectedText(0).isNullOrEmpty()) { say("apagado"); return }
        val c = ic.getTextBeforeCursor(1, 0)?.toString()?.firstOrNull() ?: return
        say(Speaker.nameOf(c))
    }

    /**
     * Troca para o teclado escolhido no app (ou, sem escolha, o último usado antes deste;
     * sem ele, o primeiro teclado comum ativado, como Samsung ou Google).
     */
    private fun switchKeyboard() {
        click(Key.DELETE)
        say("trocando de teclado")
        val prefs = getSharedPreferences(BrailleView.PREFS, MODE_PRIVATE)
        val chosen = prefs.getString(BrailleView.KEY_TARGET_IME, null)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabled = imm.enabledInputMethodList.map { it.id }
        val myId = android.content.ComponentName(this, BrailleKeyboardService::class.java).flattenToShortString()
        val target = when {
            chosen != null && chosen in enabled -> chosen
            else -> null
        }
        if (target != null) {
            try { switchInputMethod(target); return } catch (_: Exception) {}
        }
        if (switchToPreviousInputMethod()) return
        // último recurso: primeiro teclado ativado que não seja este nem o braille do TalkBack
        val fallback = enabled.firstOrNull { it != myId && !it.contains("brailleime") }
        if (fallback != null) {
            try { switchInputMethod(fallback); return } catch (_: Exception) {}
        }
        imm.showInputMethodPicker()
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0)
        else ic.commitText("", 1)
    }
}
