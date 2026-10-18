package it.fotofesta.tv.model

import kotlin.math.abs

/**
 * Movimento "Ken Burns" di una foto: zoom e spostamento lenti dall'inizio alla fine della sua
 * permanenza sullo schermo. È deterministico per foto, così una foto rivista si muove allo stesso modo.
 *
 * Lo spostamento è una quota del margine creato dallo zoom ((scala − 1) / 2 per lato), quindi i bordi
 * della foto non entrano mai nell'inquadratura, sia zoomando avanti sia indietro.
 */
data class KenBurnsMotion(
    val startScale: Float,
    val endScale: Float,
    /** Direzione orizzontale e verticale dello spostamento: -1, 0 o 1. */
    val directionX: Float,
    val directionY: Float,
) {
    fun scaleAt(progress: Float): Float = startScale + (endScale - startScale) * progress.coerceIn(0f, 1f)

    /** Spostamento orizzontale come frazione della larghezza dello schermo. */
    fun offsetXAt(progress: Float): Float = directionX * PAN_SHARE * margin(progress)

    /** Spostamento verticale come frazione dell'altezza dello schermo. */
    fun offsetYAt(progress: Float): Float = directionY * PAN_SHARE * margin(progress)

    private fun margin(progress: Float) = (scaleAt(progress) - 1f) / 2f

    companion object {
        const val MAX_ZOOM = 1.10f
        private const val PAN_SHARE = 0.8f

        fun forPhoto(id: String): KenBurnsMotion {
            val hash = abs(id.hashCode().toLong())
            val zoomIn = hash % 2 == 0L
            return KenBurnsMotion(
                startScale = if (zoomIn) 1f else MAX_ZOOM,
                endScale = if (zoomIn) MAX_ZOOM else 1f,
                directionX = if ((hash / 2) % 2 == 0L) 1f else -1f,
                directionY = ((hash / 4) % 3).toFloat() - 1f,
            )
        }
    }
}
