package it.fotofesta.tv.icloud

import it.fotofesta.tv.network.LinkPairingServer
import it.fotofesta.tv.network.PairingTexts

/** Pagina sul telefono per mandare alla TV il link di un album condiviso, nella lingua del telefono. */
object ICloudPairingTexts {
    private val TEXTS = mapOf(
        "it" to PairingTexts(
            language = "it",
            title = "Album condiviso iCloud",
            steps = "Su iPhone apri l'album condiviso nell'app Foto, tocca l'icona delle persone (o «Dettagli dell'album condiviso») e attiva «Sito web pubblico». Poi tocca «Condividi link», copia il link e incollalo qui.",
            label = "Link dell'album",
            send = "Invia alla TV",
            received = "Link ricevuto: guarda la TV.",
            invalid = "Questo non è il link di un album condiviso iCloud. Controlla di aver copiato il link del «Sito web pubblico».",
            expired = "Questa pagina non è più valida: inquadra di nuovo il QR sulla TV.",
        ),
        "en" to PairingTexts(
            language = "en",
            title = "iCloud Shared Album",
            steps = "On iPhone, open the shared album in the Photos app, tap the people icon (or “Shared Album Details”) and turn on “Public Website”. Then tap “Share Link”, copy the link and paste it here.",
            label = "Album link",
            send = "Send to the TV",
            received = "Link received: look at the TV.",
            invalid = "This isn’t an iCloud shared album link. Make sure you copied the “Public Website” link.",
            expired = "This page is no longer valid: scan the QR code on the TV again.",
        ),
        "fr" to PairingTexts(
            language = "fr",
            title = "Album partagé iCloud",
            steps = "Sur l’iPhone, ouvrez l’album partagé dans l’app Photos, touchez l’icône des personnes (ou « Détails de l’album partagé ») et activez « Site web public ». Touchez ensuite « Partager le lien », copiez le lien et collez-le ici.",
            label = "Lien de l’album",
            send = "Envoyer à la TV",
            received = "Lien reçu : regardez la TV.",
            invalid = "Ce n’est pas le lien d’un album partagé iCloud. Vérifiez que vous avez copié le lien du « Site web public ».",
            expired = "Cette page n’est plus valide : scannez à nouveau le code QR sur la TV.",
        ),
        "es" to PairingTexts(
            language = "es",
            title = "Álbum compartido de iCloud",
            steps = "En el iPhone, abre el álbum compartido en la app Fotos, toca el icono de las personas (o «Detalles del álbum compartido») y activa «Sitio web público». Después toca «Compartir enlace», copia el enlace y pégalo aquí.",
            label = "Enlace del álbum",
            send = "Enviar a la TV",
            received = "Enlace recibido: mira la TV.",
            invalid = "Este no es el enlace de un álbum compartido de iCloud. Comprueba que has copiado el enlace del «Sitio web público».",
            expired = "Esta página ya no es válida: escanea de nuevo el código QR de la TV.",
        ),
        "de" to PairingTexts(
            language = "de",
            title = "Geteiltes iCloud-Album",
            steps = "Öffne auf dem iPhone das geteilte Album in der Fotos-App, tippe auf das Personensymbol (oder „Details zum geteilten Album“) und aktiviere „Öffentliche Website“. Tippe dann auf „Link teilen“, kopiere den Link und füge ihn hier ein.",
            label = "Link des Albums",
            send = "An den Fernseher senden",
            received = "Link empfangen: Schau auf den Fernseher.",
            invalid = "Das ist kein Link zu einem geteilten iCloud-Album. Prüfe, ob du den Link der „Öffentlichen Website“ kopiert hast.",
            expired = "Diese Seite ist nicht mehr gültig: Scanne den QR-Code auf dem Fernseher erneut.",
        ),
    )

    fun forLanguage(acceptLanguage: String?): PairingTexts = TEXTS.getValue(LinkPairingServer.language(acceptLanguage, TEXTS.keys))
}
