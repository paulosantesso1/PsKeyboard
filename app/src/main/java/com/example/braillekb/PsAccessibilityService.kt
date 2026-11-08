package com.example.braillekb

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço sem lógica própria: existe para poder criar uma janela TYPE_ACCESSIBILITY_OVERLAY,
 * que recebe os toques de vários dedos mesmo com o TalkBack (ou outro leitor) ligado.
 * É o mesmo mecanismo usado pelo teclado braille do próprio TalkBack.
 */
class PsAccessibilityService : AccessibilityService() {

    private var overlay: BrailleView? = null

    override fun onServiceConnected() { instance = this; KeySounds.init(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideKeyboard(); instance = null
        return super.onUnbind(intent)
    }

    /** Cobre a tela inteira, como o teclado braille do TalkBack, para que nenhum dedo caia fora. */
    fun showKeyboard(
        onChord: (Int) -> Unit, onGesture: (Gesture) -> Unit,
    ): BrailleView {
        hideKeyboard()
        val v = BrailleView(this, onChord, onGesture, exposeToA11y = false)
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val top = 0
        val height = bounds.height()
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0; y = top
            width = bounds.width()
            this.height = height
        }
        wm.addView(v, lp)
        overlay = v
        // Pede ao sistema para NÃO interpretar os toques nesta área como exploração por toque
        // nem como gestos do leitor de tela: eles chegam direto ao teclado, com todos os dedos.
        setPassthrough(Region(0, top, lp.width, top + height))
        return v
    }

    private fun setPassthrough(region: Region) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, region)
            setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, region)
        } catch (e: Exception) {
            Log.w("PsKeyboard", "passthrough falhou", e)
        }
    }

    fun hideKeyboard() {
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(it) }
            catch (_: Exception) {}
        }
        overlay = null
        setPassthrough(Region())
    }

    companion object {
        @Volatile var instance: PsAccessibilityService? = null
    }
}
