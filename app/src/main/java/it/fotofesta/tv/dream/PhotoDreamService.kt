package it.fotofesta.tv.dream

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import it.fotofesta.tv.data.ActiveCollection
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.ui.FotoFestaTheme

/**
 * Salvaschermo di sistema: mostra a schermo intero le foto dell'ultima raccolta scelta nell'app.
 *
 * DreamService non è un'Activity, quindi fornisce da sé lifecycle e saved state richiesti da Compose.
 */
class PhotoDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val settings = SettingsRepository(this).load()
        val collection = ActiveCollection(this)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PhotoDreamService)
            setViewTreeSavedStateRegistryOwner(this@PhotoDreamService)
            setContent {
                FotoFestaTheme {
                    DreamSlideshow(settings = settings, loadPhotos = { collection.load() })
                }
            }
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}
