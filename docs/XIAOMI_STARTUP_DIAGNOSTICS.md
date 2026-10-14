# Diagnostica di avvio: Android TV / Xiaomi

Questa correzione mantiene l'avvio Leanback e aggiunge anche un launcher Android standard,
utilizzato da alcune schermate **Impostazioni > App > Apri**. Gli errori di Cast Connect e
MediaSession non impediscono piu l'apertura della schermata iniziale.

## Se l'app torna alla schermata Home

1. Aprire di nuovo FotoFesta TV dall'elenco delle app della TV.
2. Se l'ultimo arresto era un'eccezione non gestita, apparira la schermata **FotoFesta TV - diagnostica avvio**.
3. Fotografare con il telefono la riga dell'errore e il testo sottostante e condividerli per identificare la causa.
4. Il tasto **Riprova FotoFesta TV** riprova l'avvio.

Il rapporto e memorizzato **solo sulla TV**, non viene caricato su servizi esterni e viene cancellato
quando viene mostrato. La schermata nativa non usa Jetpack Compose: puo apparire anche
se il crash riguarda la composizione dell'interfaccia. Un arresto del processo
senza eccezione Java/Kotlin (es. SIGSEGV, sistema che termina il processo) potrebbe
non produrre il rapporto; in quel caso servono log Android / bug report.

## Build della correzione senza GitHub Actions

Scaricare lo ZIP del ramo `fix/xiaomi-tv-startup-1.2.1`,
caricarlo nel notebook Colab gia usato, eseguire tutte le celle
e installare **FotoFesta-TV-debug.apk** sulla Xiaomi.
Il numero di versione e stato aumentato a **1.2.1** (`versionCode 4`)
per aggiornare l'app senza conflitti di versione.

## Limitazioni della verifica

Questa e una correzione difensiva basata su una revisione statica.
Non e possibile confermare il problema Xiaomi o una compilazione Gradle in un
ambiente senza Android SDK e accesso alle dipendenze.
