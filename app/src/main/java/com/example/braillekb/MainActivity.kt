package com.example.braillekb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tela mínima só para ativar o teclado e escolhê-lo como teclado atual. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "1. Ative o PsKeyboard.\n2. Escolha-o como teclado atual.\n" +
                "3. Para calibrar: com o teclado aberto, segure 4 dedos na tela por 5 segundos " +
                "e siga as instruções faladas: toque os pontos 1 a 6, um de cada vez."
            textSize = 18f
        })
        root.addView(Button(this).apply {
            text = "Ativar teclado"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Escolher teclado"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        root.addView(TextView(this).apply {
            text = "\nPara usar com TalkBack ou outro leitor de tela, ative também o serviço " +
                "de acessibilidade \"PsKeyboard\". Ele só serve para receber os toques de vários dedos."
            textSize = 18f
        })
        root.addView(Button(this).apply {
            text = "Ativar serviço de acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        val prefs = getSharedPreferences(BrailleView.PREFS, MODE_PRIVATE)
        root.addView(Button(this).apply {
            text = "Restaurar posições padrão dos pontos"
            setOnClickListener { prefs.edit().remove(BrailleView.KEY_DOTS).apply() }
        })
        root.addView(Button(this).apply {
            text = "Teclado para trocar (3 dedos)"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val mine = packageName
                val list = imm.enabledInputMethodList.filter {
                    it.packageName != mine && !it.id.contains("brailleime")
                }
                val names = arrayOf("Automático (último usado)") +
                    list.map { it.loadLabel(packageManager).toString() }.toTypedArray()
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Trocar para qual teclado?")
                    .setItems(names) { _, which ->
                        val e = prefs.edit()
                        if (which == 0) e.remove(BrailleView.KEY_TARGET_IME)
                        else e.putString(BrailleView.KEY_TARGET_IME, list[which - 1].id)
                        e.apply()
                    }.show()
            }
        })
        setContentView(root)
    }
}
