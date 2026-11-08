package com.example.braillekb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

enum class Gesture { SPACE, BACKSPACE, NEWLINE, DELETE_WORD, SEND, CLOSE, SWITCH_KEYBOARD, OPEN_SETTINGS, NEXT_MODE }

/**
 * Modo "mesa" (tabletop): segure 6 dedos por ~0,6 s para calibrar. Os 3 dedos da esquerda
 * (do mais perto do centro para fora) viram os pontos 1,2,3 e os da direita os pontos 4,5,6.
 * Depois, cada toque vira o ponto calibrado mais próximo; a cela é enviada quando todos
 * os dedos são levantados.
 *
 * Gestos (deslizar longe o bastante, sem precisar de TalkBack):
 *  1 dedo  -> direita: espaço | esquerda: apagar
 *  2 dedos -> direita: nova linha | esquerda: apagar palavra | cima: enviar | baixo: fechar teclado
 *  3 dedos -> esquerda ou cima: trocar para outro teclado (Samsung, Google...)
 *  3 dedos -> direita ou baixo: abrir as configurações do teclado
 *  (direções sempre do ponto de vista do usuário)
 */
class BrailleView(
    context: Context,
    private val onChord: (Int) -> Unit,
    private val onGesture: (Gesture) -> Unit,
    private val exposeToA11y: Boolean = true,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val swipeMin = 80 * density
    private val holdMs = 5000L
    private val handler = Handler(Looper.getMainLooper())

    private var dotPoints: Array<PointF>? = null     // índice 0..5 = pontos 1..6
    private val start = HashMap<Int, PointF>()
    private val last = HashMap<Int, PointF>()
    private var chord = 0
    private var maxPointers = 0
    private var calibrateArmed = false
    private var consumed = false                      // calibrou: ignora essa sequência de toques
    private var litMask = 0
    private var userCalibrated = context.getSharedPreferences("pskeyboard", Context.MODE_PRIVATE).contains("dots")

    private var activeCount = 0                       // dedos atualmente na tela
    private var calibrating = false
    private var calStep = 0
    private val calPoints = arrayOfNulls<PointF>(6)

    private val promptsCal = arrayOf(
        "Ponto 1. Mão esquerda, dedo indicador. Toque.",
        "Ponto 2. Mão esquerda, dedo médio. Toque.",
        "Ponto 3. Mão esquerda, dedo anelar. Toque.",
        "Ponto 4. Mão direita, dedo indicador. Toque.",
        "Ponto 5. Mão direita, dedo médio. Toque.",
        "Ponto 6. Mão direita, dedo anelar. Toque.",
    )

    // 4 dedos segurados por 5 s: começa (ou reinicia) a calibração guiada
    private val calibrateRunnable = Runnable {
        if (activeCount == 4) beginCalibration()
    }

    private fun beginCalibration() {
        calibrating = true; calStep = 0
        consumed = true; chord = 0; litMask = 0
        for (i in calPoints.indices) calPoints[i] = null
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        KeySounds.play(context, Key.RETURN)
        speak("Calibração. Levante os dedos. " + promptsCal[0])
        invalidate()
    }

    private fun recordCalibrationPoint(p: PointF) {
        calPoints[calStep] = PointF(p.x, p.y)
        KeySounds.play(context, Key.STANDARD)
        calStep++
        if (calStep < 6) {
            speak(promptsCal[calStep])
        } else {
            dotPoints = Array(6) { calPoints[it]!! }
            userCalibrated = true
            saveCalibration()
            updateDotRects()
            calibrating = false
            speak("Calibração concluída.")
            KeySounds.play(context, Key.RETURN)
        }
        invalidate()
    }

    private fun saveCalibration() {
        val pts = dotPoints ?: return
        if (width == 0 || height == 0) return
        val txt = pts.joinToString(";") { "${it.x / width},${it.y / height}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DOTS, txt).apply()
    }

    private fun loadCalibration(w: Int, h: Int): Array<PointF>? {
        val txt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DOTS, null)
            ?: return null
        return try {
            val parts = txt.split(";").map { it.split(",").let { c -> PointF(c[0].toFloat() * w, c[1].toFloat() * h) } }
            if (parts.size == 6) parts.toTypedArray() else null
        } catch (_: Exception) { null }
    }

    private fun speak(text: String) = Speaker.speak(context, text, force = true)   // só a calibração

    // ---- Camada acessível (leitor de tela): grade fixa de nós virtuais ----
    // 0..5 = pontos 1..6 (alternam), 6 = confirmar, 7 = espaço, 8 = apagar, 9 = Enter
    private val a11yRects = Array(10) { Rect() }
    private var a11yMask = 0
    private val a11yNames = arrayOf(
        "Ponto 1", "Ponto 2", "Ponto 3", "Ponto 4", "Ponto 5", "Ponto 6",
        "Confirmar letra", "Espaço", "Apagar", "Enter",
    )

    private val a11yHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val xi = x.toInt(); val yi = y.toInt()
            for (i in a11yRects.indices) if (a11yRects[i].contains(xi, yi)) return i
            return INVALID_ID
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for (i in a11yRects.indices) ids.add(i)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            node.contentDescription = a11yNames[id]
            node.className = "android.widget.Button"
            node.setBoundsInParent(a11yRects[id])
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            if (id < 6) {
                node.isCheckable = true
                node.isChecked = a11yMask and (1 shl id) != 0
            }
        }

        override fun onPopulateEventForVirtualView(id: Int, event: android.view.accessibility.AccessibilityEvent) {
            event.contentDescription = a11yNames[id]
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            when (id) {
                in 0..5 -> { a11yMask = a11yMask xor (1 shl id); litMask = a11yMask }
                6 -> { if (a11yMask != 0) onChord(a11yMask); a11yMask = 0; litMask = 0 }
                7 -> onGesture(Gesture.SPACE)
                8 -> onGesture(Gesture.BACKSPACE)
                9 -> onGesture(Gesture.SEND)
            }
            invalidateVirtualView(id)
            invalidate()
            return true
        }
    }

    private fun touchExplorationOn(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    init {
        if (exposeToA11y) {
            ViewCompat.setAccessibilityDelegate(this, a11yHelper)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            // janela de overlay: o leitor de tela não deve navegar nem focar nada aqui
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        (exposeToA11y && a11yHelper.dispatchHoverEvent(event)) || super.dispatchHoverEvent(event)

    /**
     * Posição padrão, sem calibrar: mão esquerda = pontos 1,2,3 (indicador, médio, anelar,
     * do centro para a borda); mão direita = pontos 4,5,6 (indicador, médio, anelar).
     * O médio fica um pouco mais alto, como num arco de dedos.
     */
    private fun defaultDots(w: Int, h: Int): Array<PointF> {
        // coordenadas do USUÁRIO: x para a direita dele, y para baixo dele
        val xs = floatArrayOf(0.40f, 0.30f, 0.20f, 0.60f, 0.70f, 0.80f)
        val ys = floatArrayOf(0.55f, 0.45f, 0.55f, 0.55f, 0.45f, 0.55f)
        val userW = abs(rS[0]) * w + abs(rS[1]) * h   // extensão da tela no eixo esquerda-direita do usuário
        val userH = abs(dS[0]) * w + abs(dS[1]) * h
        return Array(6) {
            val a = (xs[it] - 0.5f) * userW
            val b = (ys[it] - 0.5f) * userH
            PointF(w / 2f + a * rS[0] + b * dS[0], h / 2f + a * rS[1] + b * dS[1])
        }
    }

    // ---- Orientação: o celular pode estar deitado com a tela travada em retrato ----
    // rS = direção "direita do usuário" e dS = "baixo do usuário", em coordenadas da tela.
    // Fixo: não depende de sensor nem da posição do celular. Os gestos seguem sempre a tela
    // (direita da tela = espaço). A posição padrão dos pontos é espelhada (modo de costas).
    private val rS = floatArrayOf(-1f, 0f)
    private val dS = floatArrayOf(0f, 1f)

    /**
     * Eixo "direita" dos gestos, tirado da posição dos pontos (mão esquerda -> mão direita), não de
     * sensor. Assim o espaço/apagar/enviar acompanham o jeito que você posicionou/calibrou os dedos,
     * qualquer que seja a posição do celular, e nunca mudam sozinhos. Retorna (direita, baixo).
     */
    private fun gestureAxes(): Pair<FloatArray, FloatArray> {
        val pts = dotPoints
        if (pts != null) {
            var rx = (pts[3].x + pts[4].x + pts[5].x - pts[0].x - pts[1].x - pts[2].x) / 3f
            var ry = (pts[3].y + pts[4].y + pts[5].y - pts[0].y - pts[1].y - pts[2].y) / 3f
            val len = hypot(rx, ry)
            if (len > 1f) {
                rx /= len; ry /= len
                if (!userCalibrated) { rx = -rx; ry = -ry }   // layout padrão é espelhado (modo de costas)
                return Pair(floatArrayOf(rx, ry), floatArrayOf(-ry, rx))
            }
        }
        return Pair(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(calibrateRunnable)
        super.onDetachedFromWindow()
    }

    private fun updateDotRects() {
        val r = (40 * density).toInt()
        dotPoints?.forEachIndexed { i, p ->
            a11yRects[i].set(p.x.toInt() - r, p.y.toInt() - r, p.x.toInt() + r, p.y.toInt() + r)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        dotPoints = (if (userCalibrated) loadCalibration(w, h) else null) ?: defaultDots(w, h)
        val r = (40 * density).toInt()
        dotPoints?.forEachIndexed { i, p ->
            a11yRects[i].set(p.x.toInt() - r, p.y.toInt() - r, p.x.toInt() + r, p.y.toInt() + r)
        }
        // botões de apoio para leitores de tela, na faixa de baixo
        val bw = w / 4
        for (j in 0 until 4) a11yRects[6 + j].set(j * bw, (h * 0.85f).toInt(), (j + 1) * bw, h)
    }

    companion object {
        const val PREFS = "pskeyboard"
        const val KEY_DOTS = "dots"
        const val KEY_TARGET_IME = "target_ime"
    }

    /** Fala o texto digitado para o leitor de tela. */
    fun announce(text: String) {
        if (touchExplorationOn()) announceForAccessibility(text)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18 * density; textAlign = Paint.Align.CENTER
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val p = PointF(e.getX(i), e.getY(i))
                start[id] = PointF(p.x, p.y)
                last[id] = p
                activeCount++
                maxPointers = maxOf(maxPointers, activeCount)
                handler.removeCallbacks(calibrateRunnable)
                if (activeCount == 4) handler.postDelayed(calibrateRunnable, holdMs)
                if (!calibrating) {
                    dotPoints?.let { chord = chord or (1 shl nearestDot(p)) }
                    litMask = chord
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activeCount = maxOf(0, activeCount - 1)
                handler.removeCallbacks(calibrateRunnable)
                if (activeCount == 4) handler.postDelayed(calibrateRunnable, holdMs)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    last[e.getPointerId(i)]?.set(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // foi o último dedo a sair: decide o que aconteceu
                val id = e.getPointerId(e.actionIndex)
                last[id]?.set(e.getX(e.actionIndex), e.getY(e.actionIndex))
                handler.removeCallbacks(calibrateRunnable)
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (calibrating && !consumed) {
                        // calibração guiada: um toque de um dedo define o ponto atual
                        if (maxPointers == 1 && detectSwipe() == null) {
                            start.values.firstOrNull()?.let { recordCalibrationPoint(it) }
                        } else {
                            speak("Toque com um dedo só. " + promptsCal[calStep])
                        }
                    } else if (!consumed) {
                        finish()
                    }
                }
                reset()
            }
        }
        invalidate()
        return true
    }

    private fun finish() {
        val swipe = detectSwipe()
        if (swipe != null) {
            onGesture(swipe)
        } else if (chord != 0) {
            onChord(chord)
        }
    }

    private fun detectSwipe(): Gesture? {
        if (maxPointers > 3) return null
        var dx = 0f; var dy = 0f
        for ((id, s) in start) {
            val l = last[id] ?: continue
            dx += l.x - s.x; dy += l.y - s.y
        }
        dx /= start.size; dy /= start.size
        if (hypot(dx, dy) < swipeMin) return null
        // converte para o ponto de vista do usuário (direita / baixo dele)
        val (gR, down) = gestureAxes()
        val ur = dx * gR[0] + dy * gR[1]
        val ud = dx * down[0] + dy * down[1]
        dx = ur; dy = ud
        val horizontal = abs(dx) > abs(dy)
        return when {
            maxPointers == 1 && horizontal && dx > 0 -> Gesture.SPACE
            maxPointers == 1 && horizontal -> Gesture.BACKSPACE
            maxPointers == 3 && ((horizontal && dx < 0) || (!horizontal && dy < 0)) -> Gesture.SWITCH_KEYBOARD
            maxPointers == 3 -> Gesture.OPEN_SETTINGS
            maxPointers == 2 && horizontal && dx > 0 -> Gesture.NEWLINE
            maxPointers == 2 && horizontal -> Gesture.DELETE_WORD
            maxPointers == 2 && dy < 0 -> Gesture.SEND
            maxPointers == 2 -> Gesture.CLOSE
            else -> null
        }
    }

    private fun reset() {
        start.clear(); last.clear()
        chord = 0; maxPointers = 0; consumed = false; litMask = 0; activeCount = 0
    }

    private fun nearestDot(p: PointF): Int {
        val pts = dotPoints!!
        var best = 0; var bestD = Float.MAX_VALUE
        for (i in pts.indices) {
            val d = hypot(pts[i].x - p.x, pts[i].y - p.y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(20, 20, 30))
        if (calibrating) {
            c.drawText("Calibrando: ponto ${minOf(calStep + 1, 6)} de 6", width / 2f, height / 2f, textPaint)
            for (i in 0 until calStep) calPoints[i]?.let {
                dotPaint.color = Color.YELLOW
                c.drawCircle(it.x, it.y, 36 * density, dotPaint)
                c.drawText("${i + 1}", it.x, it.y + 6 * density, textPaint)
            }
            return
        }
        val pts = dotPoints ?: return
        for (i in pts.indices) {
            dotPaint.color = if (litMask and (1 shl i) != 0) Color.YELLOW else Color.DKGRAY
            c.drawCircle(pts[i].x, pts[i].y, 36 * density, dotPaint)
            c.drawText("${i + 1}", pts[i].x, pts[i].y + 6 * density, textPaint)
        }
    }
}
