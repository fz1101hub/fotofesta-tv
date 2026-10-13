package it.fotofesta.tv.data

import android.content.Context
import it.fotofesta.tv.R
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType

/** Foto dimostrative incluse nell'app, con titoli localizzati. */
object DemoPhotos {
    fun load(context: Context): List<PhotoItem> = listOf(
        photo(context, "demo:coast", R.string.demo_coast, R.drawable.demo_coast),
        photo(context, "demo:picnic", R.string.demo_picnic, R.drawable.demo_picnic),
        photo(context, "demo:mountains", R.string.demo_mountains, R.drawable.demo_mountains),
        photo(context, "demo:birthday", R.string.demo_birthday, R.drawable.demo_birthday),
    )

    private fun photo(context: Context, id: String, title: Int, drawable: Int) =
        PhotoItem(id, context.getString(title), PhotoRef.Resource(drawable), SourceType.DEMO)
}
