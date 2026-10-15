package it.fotofesta.tv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Process
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Salva l'ultima eccezione non gestita. Al successivo avvio, MainActivity puo mostrare
 * un rapporto con widget Android nativi, anche quando l'interfaccia Compose va in crash.
 * Non invia dati in rete e conserva solo l'ultimo rapporto nell'area privata dell'app.
 */
internal object StartupDiagnostics {
    private const val PREFS = "fotofesta_startup_diagnostics"
    private const val LAST_CRASH = "last_uncaught_exception"
    private const val MAX_CHARS = 12_000

    fun install(application: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = "${error.javaClass.name}: ${error.message.orEmpty()}\n" +
                    Log.getStackTraceString(error)
                // commit sincrono: Android puo terminare il processo immediatamente dopo.
                application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(LAST_CRASH, report.take(MAX_CHARS)).commit()
            } catch (_: Throwable) {
                // La gestione del crash non deve impedire al gestore di sistema di intervenire.
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else Process.killProcess(Process.myPid())
        }
    }

    fun takeLastCrash(context: Context): String? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val report = preferences.getString(LAST_CRASH, null)
        if (report != null) preferences.edit().remove(LAST_CRASH).commit()
        return report
    }

    fun showRecovery(activity: Activity, report: String, retry: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun dip(value: Int): Int = (value * dp).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 16, 31))
            setPadding(dip(36), dip(24), dip(36), dip(24))
        }
        val title = TextView(activity).apply {
            text = "FotoFesta TV - diagnostica avvio"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        val hint = TextView(activity).apply {
            text = "L'app si e chiusa per un errore durante il precedente utilizzo. " +
                "Fotografa il codice qui sotto e condividilo per individuare il problema. " +
                "Premi Riprova per tentare nuovamente l'apertura."
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setPadding(0, dip(12), 0, dip(16))
        }
        val retryButton = Button(activity).apply {
            text = "Riprova FotoFesta TV"
            setOnClickListener { retry() }
        }
        val errorText = TextView(activity).apply {
            text = report.lineSequence().take(28).joinToString("\n")
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.YELLOW)
            setTextIsSelectable(true)
            setPadding(0, dip(16), 0, 0)
        }
        column.addView(title)
        column.addView(hint)
        column.addView(retryButton)
        column.addView(errorText)
        activity.setContentView(ScrollView(activity).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
        retryButton.requestFocus()
    }
}
