package it.fotofesta.tv.guests

import android.content.Context
import it.fotofesta.tv.R
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType

/** Foto dell'invitato come elemento dello slideshow, con il nome di chi l'ha mandata se c'è. */
fun GuestPhotoStore.Photo.toPhotoItem(context: Context): PhotoItem = PhotoItem(
    id = "guest:$id",
    title = sender?.let { context.getString(R.string.guest_photo_from, it) } ?: context.getString(R.string.guest_photo),
    ref = PhotoRef.GuestPhoto(id),
    source = SourceType.GUESTS,
    sender = sender,
    receivedAtMillis = receivedAtMillis,
)

/** Video breve di un invitato: si guarda nello slideshow come gli altri video, dal file su disco. */
fun GuestVideoStore.Video.toPhotoItem(context: Context): PhotoItem = PhotoItem(
    id = "guestvideo:$id",
    title = sender?.let { context.getString(R.string.guest_video_from, it) } ?: context.getString(R.string.guest_video),
    ref = PhotoRef.LocalFile(file.absolutePath),
    source = SourceType.GUESTS,
    sender = sender,
    receivedAtMillis = receivedAtMillis,
    video = true,
)

/** Identificativo nell'archivio della festa, se l'elemento è una foto di un invitato. */
val PhotoItem.guestPhotoId: String?
    get() = (ref as? PhotoRef.GuestPhoto)?.id
