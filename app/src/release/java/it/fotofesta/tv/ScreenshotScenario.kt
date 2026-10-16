package it.fotofesta.tv

import android.content.Intent

/**
 * Nella release gli scenari per le schermate non esistono: vedi la versione in src/debug, usata
 * dall'emulatore della CI.
 */
object ScreenshotScenario {
    val name: String? get() = null

    @Suppress("UNUSED_PARAMETER")
    fun start(activity: MainActivity, viewModel: FotoFestaViewModel, intent: Intent) = Unit
}
