package it.fotofesta.tv.data

/**
 * Ordinamento "naturale" dei nomi file: i numeri vengono confrontati per valore, così
 * `IMG_2.jpg` precede `IMG_10.jpg`, come nei file manager. Le lettere ignorano maiuscole e minuscole.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                val numberA = a.substring(startA, i).trimStart('0')
                val numberB = b.substring(startB, j).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                val byValue = numberA.compareTo(numberB)
                if (byValue != 0) return byValue
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byRemaining = (a.length - i) - (b.length - j)
        return if (byRemaining != 0) byRemaining else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
