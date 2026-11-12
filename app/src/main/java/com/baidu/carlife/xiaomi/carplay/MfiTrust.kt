package com.baidu.carlife.xiaomi.carplay

import java.security.cert.CertPathValidatorException
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.CertPathValidator
import java.security.cert.PKIXCertPathChecker
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Date

internal object MfiTrust {
    // Apple Security iAPTests_data.h, commit db15acbe6a7f257a859ad9a3bb86097bfe0679d9.
    // The self-signed AACA roots are _v3ca and _root_AACA2, never certificates supplied by the peer.
    private val anchors by lazy {
        listOf("00000000", "00000002").map { id ->
            val certificate = checkNotNull(javaClass.getResourceAsStream("/mfi/apple-aaca-$id.der")).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            TrustAnchor(certificate, null)
        }.toSet()
    }

    fun validate(receiver: X509Certificate, at: Date = Date()) {
        val publicKey = receiver.publicKey as? ECPublicKey
        if (receiver.basicConstraints != -1 || publicKey?.params?.curve?.field?.fieldSize != 256 ||
            !ACCESSORY_NAME.containsMatchIn(receiver.subjectX500Principal.name)
        ) {
            throw CertPathValidatorException("Receiver is not an MFi 3 end-entity certificate")
        }
        val extension = receiver.getExtensionValue(MFI3_CAPABILITIES)
        if (extension == null || extension.size != 36 ||
            !extension.copyOf(4).contentEquals(byteArrayOf(4, 34, 4, 32))
        ) {
            throw CertPathValidatorException("Invalid MFi 3 capabilities extension")
        }
        val matching = anchors.filter { it.trustedCert.subjectX500Principal == receiver.issuerX500Principal }
        if (matching.isEmpty()) throw CertPathValidatorException("Unrecognized Apple accessory issuer")
        matching.forEach { it.trustedCert.checkValidity(at) }

        val parameters = PKIXParameters(matching.toSet()).apply {
            date = at
            // This local validation checks the pinned Apple chain and dates; it has no revocation feed.
            isRevocationEnabled = false
            addCertPathChecker(object : PKIXCertPathChecker() {
                override fun init(forward: Boolean) = Unit
                override fun isForwardCheckingSupported() = false
                override fun getSupportedExtensions() = setOf(MFI3_CAPABILITIES)
                override fun check(certificate: Certificate, unresolved: MutableCollection<String>?) {
                    if (certificate == receiver) unresolved?.remove(MFI3_CAPABILITIES)
                }
            })
        }
        val chain = CertificateFactory.getInstance("X.509").generateCertPath(listOf(receiver))
        CertPathValidator.getInstance("PKIX").validate(chain, parameters)
    }

    private const val MFI3_CAPABILITIES = "1.2.840.113635.100.6.36"
    private val ACCESSORY_NAME = Regex("(?:^|,)CN=IPA_[0-9A-Fa-f]+(?:,|$)")
}
