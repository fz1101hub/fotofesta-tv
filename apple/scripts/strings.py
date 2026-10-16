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
    "message.choose_source": ("Choose a photo source first.", "Scegli prima una sorgente di foto."),
    "message.google_skipped": ("Items skipped (only photos are shown, not videos): %ld", "Elementi ignorati (vengono mostrate solo le foto, non i video): %ld"),
    "message.google_ready": ("Google photos ready for the slideshow.", "Foto Google pronte per lo slideshow."),
    "message.google_removed": ("Google Photos disconnected: access and the list of photos were removed from this Apple TV.", "Google Foto scollegato: accesso ed elenco delle foto rimossi da questa Apple TV."),
    "message.sleep_timer": ("Slideshow paused by the sleep timer.", "Slideshow in pausa: è scattata la pausa automatica."),
    "error.google_not_configured": ("Google Photos isn't available in this version of the app. You can still use the demo photos.", "Google Foto non è disponibile in questa versione dell'app. Puoi comunque usare le foto dimostrative."),
    "error.google_access_denied": ("Authorization cancelled on the phone.", "Autorizzazione annullata sul telefono."),
    "error.google_code_expired": ("The Google code has expired. Try again.", "Il codice Google è scaduto. Riprova."),
    "error.google_selection_expired": ("The Google Photos selection has expired.", "La selezione Google Foto è scaduta."),
    "error.google_session_expired": ("Access to Google has expired. Connect again.", "L'accesso a Google è scaduto. Collegati di nuovo."),
    "error.google_selection_gone": ("Your Google Photos selection has expired. Choose the photos again from your phone.", "La selezione di Google Foto è scaduta. Scegli di nuovo le foto dal telefono."),
    "error.google_nothing_selected": ("You didn't select any photos.", "Non hai selezionato foto."),
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
    "settings.privacy": ("FotoFesta TV has no ads or analytics. Photos you pick in Google Photos are not copied to this Apple TV: they load from Google during the slideshow, and “Disconnect and delete” revokes access. Guest photos travel only over your home network and are deleted when the party ends.", "FotoFesta TV non contiene pubblicità né analytics. Le foto scelte in Google Foto non vengono copiate su questa Apple TV: si caricano da Google durante lo slideshow, e “Disconnetti e cancella” revoca l'accesso. Le foto degli invitati passano solo dalla rete di casa e vengono cancellate a fine festa."),
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
    "google.unavailable": ("Connection not available", "Collegamento non disponibile"),
    "google.photos_ready": ("Photos ready: %ld", "Foto pronte: %ld"),
    "google.streaming_note": ("Photos stay in Google Photos and load during the slideshow: the Apple TV keeps no copies.", "Le foto restano su Google Foto e si caricano durante lo slideshow: la Apple TV non ne conserva copie."),
    "google.available_until": ("Available until %@. After that, choose them again from your phone.", "Disponibili fino al %@. Poi andranno scelte di nuovo dal telefono."),
    "google.complete_body": ("Start the slideshow now or go back to change the settings.", "Puoi avviare subito lo slideshow o tornare indietro per cambiare le impostazioni."),
    "home.guests_title": ("Guest photos", "Foto degli invitati"),
    "home.guests_body": ("Guests send photos from their phones by scanning a QR code.", "Gli invitati mandano le foto dal telefono inquadrando un QR."),
    "source.guests": ("Guest photos · photos: %ld", "Foto degli invitati · foto: %ld"),
    "source.with_guests": ("%@ + from guests: %ld", "%@ + dagli invitati: %ld"),
    "guest.photo": ("Guest photo", "Foto di un invitato"),
    "guest.photo_from": ("Photo from %@", "Foto di %@"),
    "guests.kicker": ("Party mode", "Modalità festa"),
    "guests.title": ("Guest photos", "Foto degli invitati"),
    "guests.subtitle": ("Guests scan the QR code and send photos from their phones over your home network.", "Gli invitati inquadrano il QR e mandano le foto dal telefono attraverso la rete di casa."),
    "guests.intro": ("Photos stay on the Apple TV only during the party: in memory, with a small temporary folder as a fallback. Ending the party deletes them all.", "Le foto restano sulla Apple TV solo durante la festa: in memoria, con una piccola cartella temporanea di riserva. Terminando la festa vengono cancellate tutte."),
    "guests.same_network": ("Phones must be on the same network as the Apple TV.", "I telefoni devono essere sulla stessa rete della Apple TV."),
    "guests.start": ("Start receiving photos", "Inizia a ricevere foto"),
    "guests.qr_send": ("QR code to send photos", "QR per mandare le foto"),
    "guests.scan_send": ("Scan to send photos", "Inquadra per mandare le foto"),
    "guests.qr_wifi": ("QR code to join the Wi‑Fi", "QR per entrare nella Wi‑Fi"),
    "guests.scan_wifi": ("Scan to join “%@”", "Inquadra per entrare in “%@”"),
    "guests.no_network": ("Connect the Apple TV to your home network (Wi‑Fi or cable) to receive photos.", "Collega la Apple TV alla rete di casa (Wi‑Fi o cavo) per ricevere le foto."),
    "guests.server_failed": ("The Apple TV can't receive photos right now. End the party and start it again.", "La Apple TV non riesce a ricevere foto in questo momento. Termina la festa e ricominciala."),
    "guests.starting": ("Starting…", "Avvio in corso…"),
    "guests.shown": ("Photos in the slideshow: %ld", "Foto nello slideshow: %ld"),
    "guests.pending": ("Photos to approve: %ld", "Foto da approvare: %ld"),
    "guests.approval": ("Approve photos", "Approvazione delle foto"),
    "guests.approval_body": ("New photos appear only after you approve them with the remote.", "Le foto nuove compaiono solo dopo che le approvi con il telecomando."),
    "guests.qr_in_slideshow": ("QR code in the slideshow", "QR nello slideshow"),
    "guests.wifi_set": ("Set up Wi‑Fi QR code", "Imposta il QR della Wi‑Fi"),
    "guests.wifi_change": ("Change Wi‑Fi QR code", "Modifica il QR della Wi‑Fi"),
    "guests.wifi_remove": ("Remove Wi‑Fi QR code", "Rimuovi il QR della Wi‑Fi"),
    "guests.wifi_body": ("Lets guests join your Wi‑Fi without typing the password.", "Gli invitati entrano nella tua Wi‑Fi senza scrivere la password."),
    "guests.end": ("End party and delete photos", "Termina la festa e cancella le foto"),
    "guests.end_confirm_title": ("End the party?", "Terminare la festa?"),
    "guests.end_confirm_body": ("Photos received will be deleted from the Apple TV: %ld", "Le foto ricevute verranno cancellate dalla Apple TV: %ld"),
    "guests.waiting_title": ("Send your photos!", "Manda le tue foto!"),
    "guests.waiting_body": ("Scan the QR code with your phone: photos will appear here within a few seconds.", "Inquadra il QR con il telefono: le foto compariranno qui in pochi secondi."),
    "guests.slideshow_cta": ("Send your photos", "Manda le tue foto"),
    "message.guests_ended": ("Party ended: guest photos were deleted from the Apple TV.", "Festa terminata: le foto degli invitati sono state cancellate dalla Apple TV."),
    "review.kicker": ("Guest photos", "Foto degli invitati"),
    "review.title": ("Photos to approve", "Foto da approvare"),
    "review.show": ("Show", "Mostra"),
    "review.discard": ("Discard", "Scarta"),
    "review.approve_all": ("Approve all", "Approva tutte"),
    "review.empty": ("No photos waiting for approval.", "Nessuna foto in attesa di approvazione."),
    "wifi.title": ("Wi‑Fi for guests", "Wi‑Fi per gli invitati"),
    "wifi.subtitle": ("Enter your network name and password: the Apple TV will show a QR code to join it. The password stays in this Apple TV's keychain.", "Scrivi nome e password della rete: la Apple TV mostrerà un QR per entrarci. La password resta nel Portachiavi di questa Apple TV."),
    "wifi.name": ("Network name", "Nome della rete"),
    "wifi.password": ("Password (empty for an open network)", "Password (vuota per una rete aperta)"),
    "wifi.save": ("Save", "Salva"),
    "wifi.error_name": ("Enter the network name.", "Scrivi il nome della rete."),
    "wifi.error_name_long": ("The network name is too long.", "Il nome della rete è troppo lungo."),
    "wifi.error_password": ("The password must be 8 to 63 characters long.", "La password deve avere da 8 a 63 caratteri."),
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
