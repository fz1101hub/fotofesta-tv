package it.fotofesta.tv.smb

import org.junit.Assert.assertEquals
import org.junit.Test

/** La TV parla SMB solo con i computer della rete di casa: il controllo precede ogni connessione. */
class SmbSharesPolicyTest {
    private fun failure(shares: SmbShares, address: String): SmbException.Reason =
        (runCatching { shares.login(SmbAddress.parse(address)!!, SmbLogin()) }.exceptionOrNull() as SmbException).reason

    @Test
    fun computersOutsideTheHomeNetworkAreRefusedWithoutConnecting() {
        SmbShares(setOf("jpg"), connectTimeoutMs = 500, responseTimeoutMs = 1_000).use { shares ->
            assertEquals(SmbException.Reason.NOT_LOCAL, failure(shares, "8.8.8.8"))
            assertEquals(SmbException.Reason.NOT_LOCAL, failure(shares, "127.0.0.1:1"))
        }
    }

    @Test
    fun loopbackOnlyWhenAllowed() {
        SmbShares(setOf("jpg"), connectTimeoutMs = 500, responseTimeoutMs = 1_000, allowLoopback = true).use { shares ->
            // Porta chiusa: si arriva a provare la connessione.
            assertEquals(SmbException.Reason.UNREACHABLE, failure(shares, "127.0.0.1:1"))
        }
    }
}
