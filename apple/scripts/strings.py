"""Testi dell'app Apple TV: genera <lingua>.lproj/Localizable.strings e InfoPlist.strings e
verifica le chiavi.

Inglese e italiano sono qui sotto; francese, spagnolo e tedesco sono in translations/<lingua>.json,
con le stesse chiavi e gli stessi segnaposto.

Uso: python3 apple/scripts/strings.py   (esce con errore se una chiave usata in L("…") manca)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRANSLATIONS = Path(__file__).resolve().parent / "translations"
EXTRA_LANGUAGES = ("fr", "es", "de")

# Testi di Info.plist mostrati dal sistema (richieste di accesso alla rete locale e alle foto).
INFO_PLIST = {
    "en": {
        "NSLocalNetworkUsageDescription": "FotoFesta TV receives the photos your guests send from their phones and shows the photos of the media servers (NAS, PC) on your home network.",
        "NSPhotoLibraryUsageDescription": "FotoFesta TV shows the albums of your Photos library in the slideshow. Photos stay in your library and aren’t sent anywhere.",
    },
    "it": {
        "NSLocalNetworkUsageDescription": "FotoFesta TV riceve le foto che gli invitati mandano dal telefono e mostra le foto dei server multimediali (NAS, PC) della rete di casa.",
        "NSPhotoLibraryUsageDescription": "FotoFesta TV mostra nello slideshow gli album della tua libreria Foto. Le foto restano nella libreria e non vengono inviate altrove.",
    },
}

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
    "message.google_skipped": ("Items skipped (only photos and videos are shown): %ld", "Elementi ignorati (si mostrano solo foto e video): %ld"),
    "message.google_ready": ("Google photos ready for the slideshow.", "Foto Google pronte per lo slideshow."),
    "message.google_removed": ("Google Photos disconnected: access and the list of photos were removed from this Apple TV.", "Google Foto scollegato: accesso ed elenco delle foto rimossi da questa Apple TV."),
    "message.sleep_timer": ("Slideshow paused by the sleep timer.", "Slideshow in pausa: è scattata la pausa automatica."),
    "error.google_not_configured": ("Google Photos isn't available in this version of the app. You can still use the demo photos.", "Google Foto non è disponibile in questa versione dell'app. Puoi comunque usare le foto dimostrative."),
    "error.google_access_denied": ("Authorization cancelled on the phone.", "Autorizzazione annullata sul telefono."),
    "error.google_code_expired": ("Time ran out for the authorization. Try again.", "Tempo scaduto per l’autorizzazione. Riprova."),
    "error.google_no_network": ("The Apple TV isn’t connected to the home network, so your phone couldn’t reach it.", "La Apple TV non è collegata alla rete di casa: il telefono non potrebbe raggiungerla."),
    "error.google_consent_failed": ("Google refused the authorization request. Check the OAuth client configuration.", "Google ha rifiutato la richiesta di autorizzazione. Controlla la configurazione del client OAuth."),
    "error.google_selection_expired": ("The Google Photos selection has expired.", "La selezione Google Foto è scaduta."),
    "error.google_session_expired": ("Access to Google has expired. Connect again.", "L'accesso a Google è scaduto. Collegati di nuovo."),
    "error.google_selection_gone": ("Your Google Photos selection has expired. Choose the photos again from your phone.", "La selezione di Google Foto è scaduta. Scegli di nuovo le foto dal telefono."),
    "error.google_nothing_selected": ("You didn't select any photos.", "Non hai selezionato foto."),
    "error.google_http": ("Google service unavailable (%ld).", "Servizio Google non disponibile (%ld)."),
    "error.network": ("Network unavailable. Check the Apple TV's internet connection.", "Rete non disponibile. Controlla la connessione internet della Apple TV."),
    "error.generic": ("Something went wrong. Check the network and try again.", "Operazione non riuscita. Controlla la rete e riprova."),
    "action.start_slideshow": ("Start slideshow", "Avvia slideshow"),
    "action.settings": ("Settings", "Impostazioni"),
    "action.retry": ("Try again", "Riprova"),
    "action.cancel": ("Cancel", "Annulla"),
    "home.google_title": ("Google Photos", "Google Foto"),
    "slideshow.empty": ("No photos to show", "Nessuna foto da mostrare"),
    "slideshow.sleep_at": ("Pauses at %@", "Pausa alle %@"),
    "slideshow.hint_playing": ("◀ ▶ change photo  ·  Select or Play/Pause to pause  ·  Menu to exit", "◀ ▶ cambia foto  ·  Centro o Play/Pausa per la pausa  ·  Menu per uscire"),
    "slideshow.hint_paused": ("Paused  ·  Select or Play/Pause to resume", "In pausa  ·  Centro o Play/Pausa per riprendere"),
    "settings.title": ("Settings", "Impostazioni"),
    "settings.interval": ("Time per photo", "Durata di ogni foto"),
    "settings.transition": ("Transition", "Transizione"),
    "transition.fade": ("Fade", "Dissolvenza"),
    "transition.slide": ("Slide", "Scorrimento"),
    "transition.zoom": ("Gentle zoom", "Zoom dolce"),
    "settings.fit": ("Vertical photos", "Foto verticali"),
    "fit.contain": ("Black bars", "Bande nere"),
    "fit.crop": ("Fill the screen", "Riempi lo schermo"),
    "settings.shuffle": ("Shuffle", "Ordine casuale"),
    "settings.ken_burns": ("Slow pan and zoom", "Movimento lento"),
    "settings.reduced_motion": ("Reduce motion", "Riduci movimento"),
    "settings.clock": ("Show clock", "Mostra orologio"),
    "settings.sleep_timer": ("Sleep timer", "Pausa automatica"),
    "settings.off": ("Off", "Disattivata"),
    "settings.minutes": ("%ld min", "%ld min"),
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
    "google.phone_steps": ("2. On the page that opens, tap “Continue with Google” and choose the account with your photos.", "2. Nella pagina che si apre tocca «Continua con Google» e scegli l’account con le tue foto."),
    "google.same_network": ("The phone must be on the same Wi‑Fi as the Apple TV. The page stays open for ten minutes.", "Il telefono deve essere nella stessa Wi‑Fi della Apple TV. La pagina resta aperta dieci minuti."),
    "google.scope_note": ("We only request access to the photos you choose.", "Richiediamo soltanto l’accesso alle foto che sceglierai."),
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
    "source.guests": ("Guest photos · photos: %ld", "Foto degli invitati · foto: %ld"),
    "source.with_guests": ("%@ + from guests: %ld", "%@ + dagli invitati: %ld"),
    "guest.photo": ("Guest photo", "Foto di un invitato"),
    "guest.photo_from": ("Photo from %@", "Foto di %@"),
    "guests.title": ("Guest photos", "Foto degli invitati"),
    "guests.intro": ("Photos stay on the Apple TV only during the party: in memory, with a small temporary folder as a fallback. Ending the party deletes them all.", "Le foto restano sulla Apple TV solo durante la festa: in memoria, con una piccola cartella temporanea di riserva. Terminando la festa vengono cancellate tutte."),
    "guests.same_network": ("Phones must be on the same network as the Apple TV.", "I telefoni devono essere sulla stessa rete della Apple TV."),
    "guests.qr_send": ("QR code to send photos", "QR per mandare le foto"),
    "guests.qr_wifi": ("QR code to join the Wi‑Fi", "QR per entrare nella Wi‑Fi"),
    "guests.no_network": ("Connect the Apple TV to your home network (Wi‑Fi or cable) to receive photos.", "Collega la Apple TV alla rete di casa (Wi‑Fi o cavo) per ricevere le foto."),
    "guests.server_failed": ("The Apple TV can't receive photos right now. End the party and start it again.", "La Apple TV non riesce a ricevere foto in questo momento. Termina la festa e ricominciala."),
    "guests.starting": ("Starting…", "Avvio in corso…"),
    "guests.pending": ("Photos to approve: %ld", "Foto da approvare: %ld"),
    "guests.approval": ("Approve photos", "Approvazione delle foto"),
    "guests.approval_body": ("New photos appear only after you approve them with the remote.", "Le foto nuove compaiono solo dopo che le approvi con il telecomando."),
    "guests.qr_in_slideshow": ("QR code in the slideshow", "QR nello slideshow"),
    "guests.airplay_tip": ("Photos sent with AirPlay cover the slideshow and don't join the party. To prevent this, in Settings › AirPlay and HomeKit limit AirPlay access to people sharing this home.", "Le foto mandate con AirPlay coprono lo slideshow e non entrano nella festa. Per evitarlo, in Impostazioni › AirPlay e HomeKit limita l'accesso ad AirPlay alle persone che condividono questa casa."),
    "guests.end": ("End party and delete photos", "Termina la festa e cancella le foto"),
    "guests.end_confirm_title": ("End the party?", "Terminare la festa?"),
    "guests.end_confirm_body": ("Photos received will be deleted from the Apple TV: %ld", "Le foto ricevute verranno cancellate dalla Apple TV: %ld"),
    "guests.waiting_title": ("Send your photos!", "Manda le tue foto!"),
    "guests.waiting_body": ("Scan the QR code with your phone: photos will appear here within a few seconds.", "Inquadra il QR con il telefono: le foto compariranno qui in pochi secondi."),
    "guests.slideshow_cta": ("Send your photos", "Manda le tue foto"),
    "message.guests_ended": ("Party ended: guest photos were deleted from the Apple TV.", "Festa terminata: le foto degli invitati sono state cancellate dalla Apple TV."),
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
    "games.title": ("Mini-games with your photos", "Minigiochi con le tue foto"),
    "games.subtitle": ("Use the remote. No scores or data leave the Apple TV.", "Usa il telecomando. Nessun punteggio o dato lascia la Apple TV."),
    "games.memory_title": ("Find the pair", "Trova la coppia"),
    "games.memory_body": ("Find the photo pairs in as few moves as possible.", "Trova le coppie di foto nel minor numero di mosse."),
    "games.mystery_title": ("Mystery photo", "Foto misteriosa"),
    "games.mystery_body": ("Guess the blurred memory among several options.", "Indovina il ricordo sfocato tra più possibilità."),
    "games.active_collection": ("Active collection: %ld photos", "Raccolta attiva: %ld foto"),
    "games.need_photos": ("At least %ld photos are needed: choose a source on the home screen.", "Servono almeno %ld foto: scegli una sorgente dalla schermata principale."),
    "memory.kicker": ("Find the pair · moves: %ld", "Trova la coppia · mosse: %ld"),
    "memory.complete": ("You found all the memories in %ld moves!", "Hai trovato tutti i ricordi in %ld mosse!"),
    "memory.find_pairs": ("Turn over two cards at a time", "Gira due carte alla volta"),
    "memory.new_game": ("New game", "Nuova partita"),
    "memory.card_matched": ("Pair found: %@", "Coppia trovata: %@"),
    "memory.card_covered": ("Face-down card %ld", "Carta coperta %ld"),
    "games.puzzle_title": ("Puzzle", "Puzzle"),
    "games.puzzle_body": ("Swap the pieces to put the photo back together. Three levels, from 6 to 24 pieces.", "Scambia i pezzi e ricomponi la foto. Tre livelli, da 6 a 24 pezzi."),
    "puzzle.choose_level": ("Choose a level", "Scegli il livello"),
    "puzzle.choose_level_body": ("Select a piece, then the one to swap it with, until the photo is whole again.", "Scegli un pezzo, poi quello con cui scambiarlo, finché la foto torna intera."),
    "puzzle.level_easy": ("Easy", "Facile"),
    "puzzle.level_medium": ("Medium", "Medio"),
    "puzzle.level_hard": ("Hard", "Difficile"),
    "puzzle.kicker": ("Puzzle · moves: %ld", "Puzzle · mosse: %ld"),
    "puzzle.pick_first": ("Select a piece", "Scegli un pezzo"),
    "puzzle.pick_second": ("Now select the piece to swap it with", "Ora scegli il pezzo con cui scambiarlo"),
    "puzzle.complete": ("Photo complete in %ld moves!", "Foto ricomposta in %ld mosse!"),
    "puzzle.show_photo": ("Show photo", "Mostra la foto"),
    "puzzle.hide_photo": ("Hide photo", "Nascondi la foto"),
    "puzzle.change_level": ("Change level", "Cambia livello"),
    "puzzle.cell": ("Square %ld", "Casella %ld"),
    "puzzle.cell_selected": ("Square %ld, selected", "Casella %ld, scelta"),
    "puzzle.cell_correct": ("Square %ld, piece in the right place", "Casella %ld, pezzo al posto giusto"),
    "puzzle.pieces.one": ("%ld piece", "%ld pezzo"),
    "puzzle.pieces.other": ("%ld pieces", "%ld pezzi"),
    "games.who_title": ("Who took it?", "Chi l'ha scattata?"),
    "games.who_body": ("Guess which guest sent the photo. Played with the party photos.", "Indovina quale invitato ha mandato la foto. Si gioca con le foto della festa."),
    "games.who_unavailable": ("Needs party photos signed by at least two guests.", "Servono le foto della festa, firmate da almeno due invitati."),
    "who.unavailable_title": ("Party photos needed", "Servono le foto della festa"),
    "who.kicker": ("Who took it? · score: %ld of %ld", "Chi l'ha scattata? · punti: %ld su %ld"),
    "who.question": ("Who sent this photo?", "Chi ha mandato questa foto?"),
    "who.correct": ("Right! %@ sent it.", "Giusto! L'ha mandata %@."),
    "who.wrong": ("No, %@ sent it.", "No: l'ha mandata %@."),
    "who.next": ("Next photo", "Prossima foto"),
    "who.photo": ("Photo to guess", "Foto da indovinare"),
    "phone.title": ("Answer on your phone", "Rispondi dal telefono"),
    "phone.scan": ("Scan the party QR code: the question appears on the page.", "Inquadra il QR della festa: la domanda compare sulla pagina."),
    "phone.leaderboard": ("Leaderboard", "Classifica"),
    "phone.mode_phones": ("With phones", "Con i telefoni"),
    "phone.mode_remote": ("With the remote", "Con il telecomando"),
    "phone.reveal": ("Show the answer", "Mostra la risposta"),
    "phone.reveal_who": ("%@ sent it.", "L'ha mandata %@."),
    "phone.answers.one": ("%ld answer", "%ld risposta"),
    "phone.answers.other": ("%ld answers", "%ld risposte"),
    "phone.points.one": ("%ld point", "%ld punto"),
    "phone.points.other": ("%ld points", "%ld punti"),
    "settings.videos": ("Videos", "Video"),
    "settings.videos_body": ("Shown with the photos, from start to finish. Games use photos only.", "Insieme alle foto, dall'inizio alla fine. I giochi usano solo le foto."),
    "settings.video_sound": ("Video sound", "Audio dei video"),
    "settings.video_sound_body": ("Turn it off to watch videos without sound.", "Disattivalo per guardare i video senza audio."),
    "video.unsupported": ("This video can't be played on Apple TV.", "Questo video non si può riprodurre su Apple TV."),
    "guests.awards": ("Party awards", "Premi della festa"),
    "awards.title": ("The party awards", "I premi della festa"),
    "awards.golden_lens": ("Golden lens", "Obiettivo d'oro"),
    "awards.first": ("First photo of the party", "Prima foto della festa"),
    "awards.last": ("Grand finale", "Gran finale"),
    "awards.anonymous": ("A guest", "Un invitato"),
    "awards.at": ("at %@", "alle %@"),
    "awards.photo_count.one": ("%ld photo", "%ld foto"),
    "awards.photo_count.other": ("%ld photos", "%ld foto"),
    "awards.guest_count.one": ("%ld guest", "%ld invitato"),
    "awards.guest_count.other": ("%ld guests", "%ld invitati"),
    "settings.layout": ("Layout", "Disposizione"),
    "layout.single": ("One photo at a time", "Una foto alla volta"),
    "layout.single_body": ("The photo fills the screen.", "La foto riempie lo schermo."),
    "layout.mosaic": ("Mosaic", "Mosaico"),
    "layout.mosaic_body": ("The new photo large, with the two previous ones beside it.", "La foto nuova in grande, accanto le due precedenti."),
    "settings.countdown": ("Countdown", "Conto alla rovescia"),
    "settings.countdown_body": ("In the slideshow, during the last hour before the chosen time.", "Nello slideshow, nell'ultima ora prima dell'orario scelto."),
    "settings.countdown_time": ("Time", "Orario"),
    "settings.countdown_event": ("Occasion", "Momento"),
    "countdown.in": ("%@ in %@", "%@ tra %@"),
    "countdown.midnight": ("Midnight", "Mezzanotte"),
    "countdown.midnight_message": ("Happy New Year!", "Buon anno!"),
    "countdown.cake": ("Cake", "Torta"),
    "countdown.cake_message": ("Here comes the cake!", "Arriva la torta!"),
    "countdown.toast": ("Toast", "Brindisi"),
    "countdown.toast_message": ("Raise your glasses!", "Alziamo i calici!"),
    "countdown.surprise": ("Surprise", "Sorpresa"),
    "countdown.surprise_message": ("Here we go!", "Ci siamo!"),
    "mystery.prompt": ("Choose the right title", "Scegli il titolo corretto"),
    "mystery.hidden_photo": ("Hidden photo", "Foto nascosta"),
    "mystery.solved": ("Correct: %@", "Esatto: %@"),
    "mystery.question": ("Which memory is hidden?", "Quale ricordo è nascosto?"),
    "mystery.new_photo": ("New photo", "Nuova foto"),
    "mystery.correct": ("Correct answer!", "Risposta corretta!"),
    "mystery.wrong": ("Not this one. The photo is now sharper.", "Non è questa. La foto ora è più nitida."),
    "collection.demo": ("Demo photos", "Foto demo"),
    "fit.blur": ("Blurred background", "Sfondo sfocato"),
    "fit.blur_body": ("Fill the screen with a blurred background", "Riempiono lo schermo con uno sfondo sfocato"),
    "fit.contain_body": ("Whole photo, with black bars at the sides", "Foto intera, con bande nere ai lati"),
    "fit.crop_body": ("Photos are cropped to fill the screen", "Le foto vengono tagliate per riempire lo schermo"),
    "guests.end_short": ("End the party", "Termina la festa"),
    "guests.party_started": ("The party has started", "La festa è iniziata"),
    "guests.qr_step_send": ("2 · Send photos", "2 · Manda le foto"),
    "guests.qr_step_wifi": ("1 · Join the Wi‑Fi", "1 · Entra nella Wi‑Fi"),
    "guests.send_body": ("Scan the code with your phone camera. You need to be on the home Wi‑Fi.", "Inquadra il codice con la fotocamera del telefono. Serve la Wi‑Fi di casa."),
    "guests.send_body_network": ("Scan the code with your phone camera. You need the home Wi‑Fi: network “%@”.", "Inquadra il codice con la fotocamera del telefono. Serve la Wi‑Fi di casa: rete “%@”."),
    "guests.send_title": ("Send your photos to the TV", "Manda le tue foto alla TV"),
    "guests.step_1": ("Guests scan the QR code with their phone", "Gli invitati inquadrano il QR con il telefono"),
    "guests.step_2": ("They choose photos, with no app or account", "Scelgono le foto, senza app né account"),
    "guests.step_3": ("Photos appear in the slideshow within seconds", "Le foto compaiono nello slideshow in pochi secondi"),
    "guests.wifi": ("Wi‑Fi QR code", "QR della Wi‑Fi"),
    "home.choose_photos": ("Choose your photos", "Scegli le foto"),
    "home.collection_kicker": ("Collection in use", "Raccolta in uso"),
    "home.in_use": ("In use", "In uso"),
    "home.party_running": ("Party in progress", "Festa in corso"),
    "home.start_party": ("Start the party", "Avvia la festa"),
    "home.tile_demo_body": ("To try the app", "Per provare l’app"),
    "home.tile_games": ("Games", "Giochi"),
    "home.tile_google_body": ("Pick on your phone", "Scegli dal telefono"),
    "home.tile_guests_body": ("Photos from phones, with a QR code", "Foto dal telefono, con un QR"),
    "home.tile_guests_title": ("Guests", "Invitati"),
    "settings.cat_party": ("Party", "Festa"),
    "settings.cat_privacy": ("Account and privacy", "Account e privacy"),
    "settings.cat_slideshow": ("Slideshow", "Slideshow"),
    "settings.wifi_none": ("Not set", "Non impostata"),
    "settings.wifi_remove_item": ("Remove the Wi‑Fi", "Rimuovi la Wi‑Fi"),
    "slideshow.qr_body": ("Scan with your phone", "Inquadra con il telefono"),
    "slideshow.received_at": ("Arrived at %@", "Arrivata alle %@"),
    "home.collection_count.one": ("%ld photo", "%ld foto"),
    "home.collection_count.other": ("%ld photos", "%ld foto"),
    "home.collection_guests.one": ("%ld from guests", "%ld dagli invitati"),
    "home.collection_guests.other": ("%ld from guests", "%ld dagli invitati"),
    "home.party_chip.one": ("Party in progress · %ld photo", "Festa in corso · %ld foto"),
    "home.party_chip.other": ("Party in progress · %ld photos", "Festa in corso · %ld foto"),
    "settings.interval_value.one": ("%ld second", "%ld secondo"),
    "settings.interval_value.other": ("%ld seconds", "%ld secondi"),
    "guests.stat_received.one": ("photo received", "foto ricevuta"),
    "guests.stat_received.other": ("photos received", "foto ricevute"),
    "guests.stat_pending.one": ("to approve", "da approvare"),
    "guests.stat_pending.other": ("to approve", "da approvare"),
    "guests.approve_photos.one": ("Approve the photo (%ld)", "Approva la foto (%ld)"),
    "guests.approve_photos.other": ("Approve photos (%ld)", "Approva le foto (%ld)"),
    "settings.subtitle_local": ("Settings are saved only on this Apple TV.", "Le impostazioni restano solo su questa Apple TV."),
    "home.tile_settings_body": ("Slideshow, party and privacy", "Slideshow, festa e privacy"),
    "home.library_title": ("iCloud Photos", "Foto di iCloud"),
    "home.tile_library_body": ("Albums and shared albums", "Album e album condivisi"),
    "library.kicker": ("Photos on Apple TV", "Foto della Apple TV"),
    "library.title": ("iCloud Photos", "Foto di iCloud"),
    "library.subtitle": ("Choose an album from the Photos library of this Apple TV: iCloud Photos, favorites, your albums and shared albums. Photos load from iCloud as they’re shown.", "Scegli un album dalla libreria Foto di questa Apple TV: Foto di iCloud, preferiti, i tuoi album e gli album condivisi. Le foto si caricano da iCloud quando vengono mostrate."),
    "library.ask": ("FotoFesta TV needs access to the photos on this Apple TV to show your albums. They stay in your library: the app doesn’t copy them or send them anywhere.", "FotoFesta TV ha bisogno di accedere alle foto di questa Apple TV per mostrare i tuoi album. Restano nella tua libreria: l’app non le copia e non le manda da nessuna parte."),
    "library.allow": ("Allow access", "Consenti l’accesso"),
    "library.denied": ("FotoFesta TV can’t see the photos on this Apple TV. Allow access in the Apple TV Settings, under Privacy & Security › Photos, then come back here.", "FotoFesta TV non può vedere le foto di questa Apple TV. Consenti l’accesso nelle Impostazioni della Apple TV, in Privacy e sicurezza › Foto, poi torna qui."),
    "library.retry": ("Try again", "Riprova"),
    "library.none": ("No photos found. On Apple TV, turn on iCloud Photos or Shared Albums in Settings › Users and Accounts › iCloud.", "Nessuna foto trovata. Sulla Apple TV attiva Foto di iCloud o Album condivisi in Impostazioni › Utenti e account › iCloud."),
    "library.all": ("All photos", "Tutte le foto"),
    "library.favorites": ("Favorites", "Preferiti"),
    "library.untitled": ("Untitled album", "Album senza nome"),
    "library.section_library": ("Library", "Libreria"),
    "library.section_albums": ("My albums", "I miei album"),
    "library.section_shared": ("Shared albums", "Album condivisi"),
    "library.count.one": ("%ld item", "%ld elemento"),
    "library.count.other": ("%ld items", "%ld elementi"),
    "library.empty_album": ("This album has no photos or videos.", "Questo album non ha foto né video."),
    "library.album_gone": ("The chosen album is no longer in the Photos library: showing the demo photos.", "L’album scelto non è più nella libreria Foto: si mostrano le foto demo."),
    "library.unavailable": ("The iCloud Photos album is no longer available: choose another one.", "L’album di Foto di iCloud non è più disponibile: scegline un altro."),
    "source.library": ("iCloud Photos · items: %ld", "Foto di iCloud · elementi: %ld"),
    "message.library_ready": ("iCloud Photos ready for the slideshow.", "Foto di iCloud pronte per lo slideshow."),
    "home.network_title": ("Network folders", "Cartelle di rete"),
    "home.tile_network_body": ("NAS, PC and media servers", "NAS, PC e server multimediali"),
    "network.kicker": ("Home network", "Rete di casa"),
    "network.title": ("Network folders", "Cartelle di rete"),
    "network.subtitle": ("Photos and videos from the media servers (DLNA) on your home network: NAS, PC, router, Plex, Jellyfin.", "Foto e video dei server multimediali (DLNA) della rete di casa: NAS, PC, router, Plex, Jellyfin."),
    "network.in_use": ("In use · %@", "In uso · %@"),
    "network.servers": ("Media servers", "Server multimediali"),
    "network.searching": ("Looking for media servers…", "Cerco i server multimediali…"),
    "network.none": ("No server found automatically. Add your NAS or PC with its IP address below, and check that media sharing (DLNA) is turned on.", "Nessun server trovato da solo. Aggiungi il NAS o il PC con il suo indirizzo IP qui sotto e controlla che la condivisione multimediale (DLNA) sia attiva."),
    "network.search_again": ("Search again", "Cerca di nuovo"),
    "network.add_title": ("Add with the address", "Aggiungi con l’indirizzo"),
    "network.address_placeholder": ("For example 192.168.1.20", "Ad esempio 192.168.1.20"),
    "network.add": ("Add", "Aggiungi"),
    "network.add_hint": ("You’ll find the address in the NAS app or in your router’s list of devices.", "L’indirizzo è nell’app del NAS o nell’elenco dei dispositivi del router."),
    "network.error_address": ("Type the IP address of a device on your home network, for example 192.168.1.20.", "Scrivi l’indirizzo IP di un dispositivo della rete di casa, ad esempio 192.168.1.20."),
    "network.error_not_found": ("No media server answers at %@. Turn on media sharing (DLNA) on the NAS or PC.", "Nessun server multimediale risponde a %@. Attiva la condivisione multimediale (DLNA) sul NAS o sul PC."),
    "network.unreachable": ("Server not reachable: check that it’s on and on the same network as the Apple TV.", "Server non raggiungibile: controlla che sia acceso e nella stessa rete della Apple TV."),
    "network.use_folder": ("Use this folder", "Usa questa cartella"),
    "network.up": ("Parent folder", "Cartella superiore"),
    "network.photos_here.one": ("%ld photo here", "%ld foto qui"),
    "network.photos_here.other": ("%ld photos here", "%ld foto qui"),
    "network.no_subfolders": ("No subfolders: use this folder or go back.", "Nessuna sottocartella: usa questa cartella o torna su."),
    "network.indexing": ("Looking for photos in the folder…", "Cerco le foto nella cartella…"),
    "network.empty": ("No photos or videos in this folder.", "Nessuna foto o video in questa cartella."),
    "network.unavailable": ("The network folder isn’t available: check that the server is on.", "La cartella di rete non è disponibile: controlla che il server sia acceso."),
    "source.network": ("Network folder · items: %ld", "Cartella di rete · elementi: %ld"),
    "message.network_ready": ("Network folder ready for the slideshow.", "Cartella di rete pronta per lo slideshow."),
}

PLACEHOLDER = re.compile(r"%(?:ld|@|d|lld)")


def escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def load_languages(errors: list) -> tuple:
    """Testi per lingua: {"en": {chiave: testo}, ...} e i testi di Info.plist."""
    languages = {
        "en": {key: values[0] for key, values in STRINGS.items()},
        "it": {key: values[1] for key, values in STRINGS.items()},
    }
    info = dict(INFO_PLIST)
    for language in EXTRA_LANGUAGES:
        path = TRANSLATIONS / f"{language}.json"
        if not path.exists():
            errors.append(f"traduzione mancante: {path.name}")
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        texts = data.get("strings", {})
        missing = sorted(STRINGS.keys() - texts.keys())
        extra = sorted(texts.keys() - STRINGS.keys())
        errors += [f"{language}: chiave mancante {key}" for key in missing]
        errors += [f"{language}: chiave sconosciuta {key}" for key in extra]
        languages[language] = texts
        info[language] = data.get("infoPlist", {})
    for language, texts in languages.items():
        for key, english in languages["en"].items():
            if key in texts and PLACEHOLDER.findall(english) != PLACEHOLDER.findall(texts[key]):
                errors.append(f"{language}: segnaposto diversi in {key}")
        if info.get(language, {}).keys() != INFO_PLIST["en"].keys():
            errors.append(f"{language}: testi di Info.plist incompleti")
    return languages, info


def load_mac(languages: dict, errors: list) -> dict:
    """Testi che sul Mac sostituiscono quelli dell'Apple TV: stesse chiavi e segnaposto, tutte le lingue."""
    data = json.loads((TRANSLATIONS / "mac.json").read_text(encoding="utf-8"))
    mac = {language: texts for language, texts in data.items() if not language.startswith("_")}
    keys = set(mac.get("en", {}))
    for language in languages:
        texts = mac.get(language, {})
        if set(texts) != keys:
            errors.append(f"mac {language}: chiavi diverse dall'inglese")
        for key, text in texts.items():
            if key not in STRINGS:
                errors.append(f"mac {language}: chiave sconosciuta {key}")
            elif PLACEHOLDER.findall(languages[language].get(key, "")) != PLACEHOLDER.findall(text):
                errors.append(f"mac {language}: segnaposto diversi in {key}")
    return mac


def write_strings(path: Path, entries: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["/* Generato da apple/scripts/strings.py: modificare lì, non qui. */", ""]
    lines += [f'"{key}" = "{escape(text)}";' for key, text in entries.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    errors = []
    used = set()
    for source in (ROOT / "FotoFestaTV/Sources").rglob("*.swift"):
        text = source.read_text()
        used |= set(re.findall(r'\bL\("([^"]+)"', text))
        # LP("chiave", n) usa le forme singolare e plurale.
        used |= {f"{key}.{form}" for key in re.findall(r'\bLP\("([^"]+)"', text) for form in ("one", "other")}
    errors += [f"chiave mancante: {key}" for key in sorted(used - STRINGS.keys())]
    unused = sorted(STRINGS.keys() - used)
    languages, info = load_languages(errors)
    resources = ROOT / "FotoFestaTV/Resources"
    for language, texts in languages.items():
        # Stesso ordine delle chiavi in tutte le lingue, per confrontare i file a colpo d'occhio.
        write_strings(resources / f"{language}.lproj/Localizable.strings", {key: texts[key] for key in STRINGS if key in texts})
        write_strings(resources / f"{language}.lproj/InfoPlist.strings", info.get(language, {}))
    # Mac: tabella "Mac" letta prima di Localizable (vedi L() in Theme.swift).
    mac = load_mac(languages, errors)
    for language, texts in mac.items():
        write_strings(ROOT / f"FotoFestaMac/Resources/{language}.lproj/Mac.strings", {key: texts[key] for key in STRINGS if key in texts})
    if unused:
        print("non usate:", ", ".join(unused))
    for error in errors:
        print("ERRORE:", error)
    print(f"{len(STRINGS)} testi in {len(languages)} lingue, {len(used)} usati nel codice")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main())
