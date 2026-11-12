package com.baidu.carlife.xiaomi.carplay

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.CertPathValidatorException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import org.junit.Assert.assertThrows
import org.junit.Test

class MfiTrustTest {
    @Test
    fun validatesAppleTestLeafAgainstTheBundledAccessoryRoot() {
        val leaf = certificate("/mfi/apple-iap-v3-leaf.der")
        MfiTrust.validate(leaf, Date.from(Instant.parse("2026-11-12T00:00:00Z")))
        assertThrows(CertificateException::class.java) {
            MfiTrust.validate(leaf, Date.from(Instant.parse("2050-01-01T00:00:00Z")))
        }
    }

    @Test
    fun rejectsTrustAnchorPresentedAsReceiverLeaf() {
        val anchor = certificate("/mfi/apple-aaca-00000000.der")
        assertThrows(CertPathValidatorException::class.java) { MfiTrust.validate(anchor) }
    }

    private fun certificate(path: String): X509Certificate =
        checkNotNull(javaClass.getResourceAsStream(path)).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
}
