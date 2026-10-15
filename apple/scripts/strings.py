"""Testi dell'app Apple TV: genera en.lproj e it.lproj/Localizable.strings e verifica le chiavi.

Uso: python3 apple/scripts/strings.py   (esce con errore se una chiave usata in L("…") manca)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# chiave: (inglese, italiano)
STRINGS = {
    "photo.unavailable": ("Photo not available", "Foto non disponibile"),
    "demo.coast": ("Coast at sunset", "Costa al tramonto"),
    "demo.picnic": ("Family picnic", "Picnic in famiglia"),
    "demo.mountains": ("Lake in the Dolomites", "Lago nelle Dolomiti"),
    "demo.birthday": ("Birthday party", "Festa di compleanno"),
    "source.demo": ("Demo photos", "Foto dimostrative"),
    "source.google": ("Google Photos · photos: %ld", "Google Foto · foto: %ld"),
    "message.google_cache_purged": ("Apple TV removed the downloaded Google photos to free up space. Import them again from the home screen.", "Apple TV ha rimosso le foto Google scaricate per liberare spazio. Importale di nuovo dalla schermata principale."),
    "message.choose_source": ("Choose a photo source first.", "Scegli prima una sorgente di foto."),
    "message.google_skipped": ("Photos not downloaded (not enough space or unavailable): %ld", "Foto non scaricate (spazio insufficiente o non disponibili): %ld"),
    "message.google_ready": ("Google photos ready for the slideshow.", "Foto Google pronte per lo slideshow."),
    "message.google_removed": ("Google Photos access and downloaded photos removed from this Apple TV.", "Accesso a Google Foto e foto scaricate rimossi da questa Apple TV."),
    "message.sleep_timer": ("Slideshow paused by the sleep timer.", "Slideshow in pausa: è scattata la pausa automatica."),
    "error.google_not_configured": ("Google Photos isn't available in this version of the app. You can still use the demo photos.", "Google Foto non è disponibile in questa versione dell'app. Puoi comunque usare le foto dimostrative."),
    "error.google_access_denied": ("Authorization cancelled on the phone.", "Autorizzazione annullata sul telefono."),
    "error.google_code_expired": ("The Google code has expired. Try again.", "Il codice Google è scaduto. Riprova."),
    "error.google_selection_expired": ("The Google Photos selection has expired.", "La selezione Google Foto è scaduta."),
    "error.google_session_expired": ("Access to Google has expired. Connect again.", "L'accesso a Google è scaduto. Collegati di nuovo."),
    "error.google_nothing_selected": ("You didn't select any photos.", "Non hai selezionato foto."),
    "error.google_download_failed": ("The selected photos couldn't be prepared.", "Non è stato possibile preparare le foto selezionate."),
    "error.cache_write": ("The photos couldn't be saved on the Apple TV.", "Impossibile salvare le foto sulla Apple TV."),
    "error.google_http": ("Google service unavailable (%ld).", "Servizio Google non disponibile (%ld)."),
    "error.network": ("Network unavailable. Check the Apple TV's internet connection.", "Rete non disponibile. Controlla la connessione internet della Apple TV."),
    "error.generic": ("Something went wrong. Check the network and try again.", "Operazione non riuscita. Controlla la rete e riprova."),
    "home.kicker": ("Your memories on the big screen", "I tuoi ricordi sul grande schermo"),
    "home.title": ("Choose where to start", "Scegli da dove iniziare"),
    "action.start_slideshow": ("Start slideshow", "Avvia slideshow"),
    "action.settings": ("Settings", "Impostazioni"),
    "action.retry": ("Try again", "Riprova"),
    "action.cancel": ("Cancel", "Annulla"),
    "home.google_title": ("Google Photos", "Google Foto"),
    "home.google_body": ("Pick photos on your phone with a QR code. No passwords to type with the remote.", "Scegli le foto dal telefono con un QR. Nessuna password da digitare con il telecomando."),
    "home.demo_title": ("Try it now", "Prova subito"),
    "home.demo_body": ("Four demo photos to explore transitions and games.", "Quattro foto demo per esplorare transizioni e giochi."),
    "home.games": ("Photo games", "Giochi con le foto"),
    "slideshow.empty": ("No photos to show", "Nessuna foto da mostrare"),
    "slideshow.info": ("%ld / %ld  ·  %ld s per photo", "%ld / %ld  ·  %ld s per foto"),
    "slideshow.sleep_at": ("Pauses at %@", "Pausa alle %@"),
    "slideshow.hint_playing": ("◀ ▶ change photo  ·  Select or Play/Pause to pause  ·  Menu to exit", "◀ ▶ cambia foto  ·  Centro o Play/Pausa per la pausa  ·  Menu per uscire"),
    "slideshow.hint_paused": ("Paused  ·  Select or Play/Pause to resume", "In pausa  ·  Centro o Play/Pausa per riprendere"),
    "settings.title": ("Settings", "Impostazioni"),
    "settings.slideshow": ("Slideshow", "Slideshow"),
    "settings.interval": ("Time per photo", "Durata di ogni foto"),
    "settings.seconds": ("%ld s", "%ld s"),
    "settings.transition": ("Transition", "Transizione"),
    "transition.fade": ("Fade", "Dissolvenza"),
    "transition.slide": ("Slide", "Scorrimento"),
    "transition.zoom": ("Gentle zoom", "Zoom dolce"),
    "settings.fit": ("Photo fit", "Adattamento foto"),
    "fit.contain": ("Whole photo", "Foto intera"),
    "fit.crop": ("Fill screen", "Riempi schermo"),
    "settings.shuffle": ("Shuffle", "Ordine casuale"),
    "settings.ken_burns": ("Slow pan and zoom", "Movimento lento"),
    "settings.reduced_motion": ("Reduce motion", "Riduci movimento"),
    "settings.clock": ("Show clock", "Mostra orologio"),
    "settings.sleep_timer": ("Sleep timer", "Pausa automatica"),
    "settings.off": ("Off", "Disattivata"),
    "settings.minutes": ("%ld min", "%ld min"),
    "settings.google_data": ("Google Photos data", "Dati Google Foto"),
    "settings.google_disconnect": ("Disconnect and delete", "Disconnetti e cancella"),
    "settings.privacy_policy": ("Privacy policy", "Informativa privacy"),
    "settings.privacy_policy_hint": ("Scan the QR code with your phone to read the full privacy policy.", "Inquadra il QR con il telefono per leggere l'informativa privacy completa."),
    "settings.privacy": ("FotoFesta TV has no ads or analytics. Photos you pick in Google Photos are downloaded to this Apple TV only, and “Disconnect and delete” removes them and revokes access.", "FotoFesta TV non contiene pubblicità né analytics. Le foto scelte in Google Foto sono scaricate solo su questa Apple TV, e “Disconnetti e cancella” le elimina e revoca l'accesso."),
    "google.kicker": ("Secure connection", "Collegamento sicuro"),
    "google.title": ("Google Photos", "Google Foto"),
    "google.subtitle": ("Use your phone: no need to type passwords with the remote.", "Usa il telefono: non dovrai digitare password con il telecomando."),
    "google.preparing": ("Preparing the connection…", "Preparazione del collegamento…"),
    "google.qr_authorize": ("QR code to authorize Google Photos", "Codice QR per autorizzare Google Foto"),
    "google.step_scan": ("1. Scan the QR code", "1. Scansiona il QR"),
    "google.open_url": ("Or open %@ on your phone and enter this code:", "Oppure apri %@ sul telefono e inserisci questo codice:"),
    "google.scope_note": ("We only request access to the photos you choose. The code expires automatically.", "Richiediamo soltanto l'accesso alle foto che sceglierai. Il codice scade automaticamente."),
    "google.qr_pick": ("QR code to choose photos", "Codice QR per scegliere le foto"),
    "google.step_pick": ("2. Choose your photos", "2. Scegli le foto"),
    "google.pick_instructions": ("Scan the QR code, select the pictures in Google Photos and tap Done.", "Scansiona il QR, seleziona le immagini in Google Foto e premi Fine."),
    "google.pick_hint": ("The Apple TV detects automatically when you're done.", "La Apple TV rileverà automaticamente quando hai finito."),
    "google.downloading": ("Preparing 4K photos", "Preparazione foto in 4K"),
    "google.progress": ("%ld of %ld", "%ld di %ld"),
    "google.cache_note": ("Photos are kept on this Apple TV so the slideshow runs without interruptions.", "Le foto restano su questa Apple TV, così lo slideshow non si interrompe."),
    "google.unavailable": ("Connection not available", "Collegamento non disponibile"),
    "google.photos_ready": ("Photos ready: %ld", "Foto pronte: %ld"),
    "google.complete_body": ("Start the slideshow now or go back to change the settings.", "Puoi avviare subito lo slideshow o tornare indietro per cambiare le impostazioni."),
    "games.kicker": ("Play together", "Gioca insieme"),
    "games.title": ("Mini-games with your photos", "Minigiochi con le tue foto"),
    "games.subtitle": ("Use the remote. No scores or data leave the Apple TV.", "Usa il telecomando. Nessun punteggio o dato lascia la Apple TV."),
    "games.memory_title": ("Memory match", "Memory dei ricordi"),
    "games.memory_body": ("Find the photo pairs in as few moves as possible.", "Trova le coppie di foto nel minor numero di mosse."),
    "games.mystery_title": ("Mystery photo", "Foto misteriosa"),
    "games.mystery_body": ("Guess the blurred memory among several options.", "Indovina il ricordo sfocato tra più possibilità."),
    "games.active_collection": ("Active collection: %ld photos", "Raccolta attiva: %ld foto"),
    "games.need_photos": ("At least %ld photos are needed: choose a source on the home screen.", "Servono almeno %ld foto: scegli una sorgente dalla schermata principale."),
    "memory.kicker": ("Memory · moves: %ld", "Memory · mosse: %ld"),
    "memory.complete": ("You found all the memories in %ld moves!", "Hai trovato tutti i ricordi in %ld mosse!"),
    "memory.find_pairs": ("Find the pairs", "Trova le coppie"),
    "memory.new_game": ("New game", "Nuova partita"),
    "memory.card_matched": ("Pair found: %@", "Coppia trovata: %@"),
    "memory.card_covered": ("Face-down card %ld", "Carta coperta %ld"),
    "mystery.prompt": ("Choose the right title", "Scegli il titolo corretto"),
    "mystery.hidden_photo": ("Hidden photo", "Foto nascosta"),
    "mystery.solved": ("Correct: %@", "Esatto: %@"),
    "mystery.question": ("Which memory is hidden?", "Quale ricordo è nascosto?"),
    "mystery.new_photo": ("New photo", "Nuova foto"),
    "mystery.correct": ("Correct answer!", "Risposta corretta!"),
    "mystery.wrong": ("Not this one. The photo is now sharper.", "Non è questa. La foto ora è più nitida."),
}

PLACEHOLDER = re.compile(r"%(?:ld|@|d|lld)")


def escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def main() -> int:
    errors = []
    for key, (english, italian) in STRINGS.items():
        if PLACEHOLDER.findall(english) != PLACEHOLDER.findall(italian):
            errors.append(f"segnaposto diversi in {key}")
    used = set()
    for source in (ROOT / "FotoFestaTV/Sources").rglob("*.swift"):
        used |= set(re.findall(r'\bL\("([^"]+)"', source.read_text()))
    errors += [f"chiave mancante: {key}" for key in sorted(used - STRINGS.keys())]
    unused = sorted(STRINGS.keys() - used)
    for index, folder in enumerate(("en.lproj", "it.lproj")):
        directory = ROOT / "FotoFestaTV/Resources" / folder
        directory.mkdir(parents=True, exist_ok=True)
        lines = ["/* Generato da apple/scripts/strings.py: modificare lì, non qui. */", ""]
        lines += [f'"{key}" = "{escape(values[index])}";' for key, values in STRINGS.items()]
        (directory / "Localizable.strings").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if unused:
        print("non usate:", ", ".join(unused))
    for error in errors:
        print("ERRORE:", error)
    print(f"{len(STRINGS)} testi, {len(used)} usati nel codice")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
