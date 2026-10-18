package it.fotofesta.tv.google

/** Archivio dei token OAuth. In produzione è [SecureTokenStore]; nei test una semplice implementazione in memoria. */
interface TokenStore {
    var accessToken: String?
    var refreshToken: String?
    var expiresAtEpochSeconds: Long
    fun clear()
}
