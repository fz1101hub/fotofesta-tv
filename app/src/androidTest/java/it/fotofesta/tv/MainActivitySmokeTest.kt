package it.fotofesta.tv

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.view.ViewGroup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test Android TV: il launcher Leanback deve aprire una schermata utilizzabile. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun launchFromTvLauncherDisplaysContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
        assertNotNull("Manca l'intent del launcher Android TV", intent)
        ActivityScenario.launch<MainActivity>(intent!!).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertNotNull(root)
                assertTrue("MainActivity non mostra alcun contenuto", root.childCount > 0)
            }
        }
    }
}
