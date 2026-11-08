package com.example.braillekb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File

enum class Key { STANDARD, SPACE, DELETE, RETURN }

/**
 * Toca os mesmos arquivos de som de tecla que o Android usa, mas diretamente, para funcionar
 * mesmo com "Sons de toque" desligado nas configurações do sistema.
 */
object KeySounds {
    private const val DIR = "/system/media/audio/ui/"
    private var pool: SoundPool? = null
    private val ids = HashMap<Key, Int>()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (pool != null) return
        appContext = context.applicationContext
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        // Sons do teclado Samsung (SIP = soft input panel); se não existirem, os do Android.
        ids[Key.STANDARD] = load(p, "TW_SIP.ogg", "KeypressStandard.ogg")
        ids[Key.SPACE] = load(p, "SIP_Modifier.ogg", "KeypressSpacebar.ogg")
        ids[Key.DELETE] = load(p, "S_SIP_Backspace.ogg", "KeypressDelete.ogg")
        ids[Key.RETURN] = load(p, "SIP_Modifier.ogg", "KeypressReturn.ogg")
        pool = p
    }

    private fun load(p: SoundPool, preferred: String, fallback: String): Int {
        val name = if (File(DIR + preferred).exists()) preferred else fallback
        return p.load(DIR + name, 1)
    }

    fun play(context: Context, key: Key = Key.STANDARD) {
        init(context)
        val id = ids[key] ?: 0
        if (id == 0 || pool?.play(id, 1f, 1f, 1, 0, 1f) == 0) {
            // arquivo não existe neste aparelho ou ainda não carregou: som padrão do sistema
            (appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }
}
