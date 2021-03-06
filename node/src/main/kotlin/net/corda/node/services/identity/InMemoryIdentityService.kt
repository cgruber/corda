package net.corda.node.services.identity

import net.corda.core.contracts.PartyAndReference
import net.corda.core.crypto.toStringShort
import net.corda.core.identity.*
import net.corda.core.internal.cert
import net.corda.core.internal.toX509CertHolder
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.UnknownAnonymousPartyException
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.trace
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import org.bouncycastle.cert.X509CertificateHolder
import java.security.InvalidAlgorithmParameterException
import java.security.PublicKey
import java.security.cert.*
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.ThreadSafe

/**
 * Simple identity service which caches parties and provides functionality for efficient lookup.
 *
 * @param identities initial set of identities for the service, typically only used for unit tests.
 */
@ThreadSafe
class InMemoryIdentityService(identities: Iterable<PartyAndCertificate> = emptySet(),
                              confidentialIdentities: Iterable<PartyAndCertificate> = emptySet(),
                              override val trustRoot: X509Certificate,
                              vararg caCertificates: X509Certificate) : SingletonSerializeAsToken(), IdentityService {
    constructor(wellKnownIdentities: Iterable<PartyAndCertificate> = emptySet(),
                confidentialIdentities: Iterable<PartyAndCertificate> = emptySet(),
                trustRoot: X509CertificateHolder) : this(wellKnownIdentities, confidentialIdentities, trustRoot.cert)

    companion object {
        private val log = contextLogger()
    }

    /**
     * Certificate store for certificate authority and intermediary certificates.
     */
    override val caCertStore: CertStore
    override val trustAnchor: TrustAnchor = TrustAnchor(trustRoot, null)
    private val keyToParties = ConcurrentHashMap<PublicKey, PartyAndCertificate>()
    private val principalToParties = ConcurrentHashMap<CordaX500Name, PartyAndCertificate>()

    init {
        val caCertificatesWithRoot: Set<X509Certificate> = caCertificates.toSet() + trustRoot
        caCertStore = CertStore.getInstance("Collection", CollectionCertStoreParameters(caCertificatesWithRoot))
        keyToParties.putAll(identities.associateBy { it.owningKey })
        principalToParties.putAll(identities.associateBy { it.name })
        confidentialIdentities.forEach { identity ->
            principalToParties.computeIfAbsent(identity.name) { identity }
        }
    }

    // TODO: Check the certificate validation logic
    @Throws(CertificateExpiredException::class, CertificateNotYetValidException::class, InvalidAlgorithmParameterException::class)
    override fun verifyAndRegisterIdentity(identity: PartyAndCertificate): PartyAndCertificate? {
        // Validate the chain first, before we do anything clever with it
        try {
            identity.verify(trustAnchor)
        } catch (e: CertPathValidatorException) {
            log.error("Certificate validation failed for ${identity.name} against trusted root ${trustAnchor.trustedCert.subjectX500Principal}.")
            log.error("Certificate path :")
            identity.certPath.certificates.reversed().forEachIndexed { index, certificate ->
                val space = (0 until index).joinToString("") { "   " }
                log.error("$space${certificate.toX509CertHolder().subject}")
            }
            throw e
        }

        // Ensure we record the first identity of the same name, first
        val identityPrincipal = identity.name.x500Principal
        val firstCertWithThisName: Certificate = identity.certPath.certificates.last { it ->
            val principal = (it as? X509Certificate)?.subjectX500Principal
            principal == identityPrincipal
        }
        if (firstCertWithThisName != identity.certificate) {
            val certificates = identity.certPath.certificates
            val idx = certificates.lastIndexOf(firstCertWithThisName)
            val firstPath = X509CertificateFactory().delegate.generateCertPath(certificates.slice(idx until certificates.size))
            verifyAndRegisterIdentity(PartyAndCertificate(firstPath))
        }

        log.trace { "Registering identity $identity" }
        keyToParties[identity.owningKey] = identity
        // Always keep the first party we registered, as that's the well known identity
        principalToParties.computeIfAbsent(identity.name) { identity }
        return keyToParties[identity.certPath.certificates[1].publicKey]
    }

    override fun certificateFromKey(owningKey: PublicKey): PartyAndCertificate? = keyToParties[owningKey]

    // We give the caller a copy of the data set to avoid any locking problems
    override fun getAllIdentities(): Iterable<PartyAndCertificate> = ArrayList(keyToParties.values)

    override fun partyFromKey(key: PublicKey): Party? = keyToParties[key]?.party
    override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? = principalToParties[name]?.party
    override fun wellKnownPartyFromAnonymous(party: AbstractParty): Party? {
        // The original version of this would return the party as-is if it was a Party (rather than AnonymousParty),
        // however that means that we don't verify that we know who owns the key. As such as now enforce turning the key
        // into a party, and from there figure out the well known party.
        val candidate = partyFromKey(party.owningKey)
        // TODO: This should be done via the network map cache, which is the authoritative source of well known identities
        return if (candidate != null) {
            require(party.nameOrNull() == null || party.nameOrNull() == candidate.name) { "Candidate party $candidate does not match expected $party" }
            wellKnownPartyFromX500Name(candidate.name)
        } else {
            null
        }
    }

    override fun wellKnownPartyFromAnonymous(partyRef: PartyAndReference) = wellKnownPartyFromAnonymous(partyRef.party)
    override fun requireWellKnownPartyFromAnonymous(party: AbstractParty): Party {
        return wellKnownPartyFromAnonymous(party) ?: throw IllegalStateException("Could not deanonymise party ${party.owningKey.toStringShort()}")
    }

    override fun partiesFromName(query: String, exactMatch: Boolean): Set<Party> {
        val results = LinkedHashSet<Party>()
        for ((x500name, partyAndCertificate) in principalToParties) {
            val party = partyAndCertificate.party
            val components = listOf(x500name.commonName, x500name.organisationUnit, x500name.organisation, x500name.locality, x500name.state, x500name.country).filterNotNull()
            components.forEach { component ->
                if (exactMatch && component == query) {
                    results += party
                } else if (!exactMatch) {
                    // We can imagine this being a query over a lucene index in future.
                    //
                    // Kostas says: We can easily use the Jaro-Winkler distance metric as it is best suited for short
                    // strings such as entity/company names, and to detect small typos. We can also apply it for city
                    // or any keyword related search in lists of records (not raw text - for raw text we need indexing)
                    // and we can return results in hierarchical order (based on normalised String similarity 0.0-1.0).
                    if (component.contains(query, ignoreCase = true))
                        results += party
                }
            }
        }
        return results
    }

    @Throws(UnknownAnonymousPartyException::class)
    override fun assertOwnership(party: Party, anonymousParty: AnonymousParty) {
        val anonymousIdentity = keyToParties[anonymousParty.owningKey] ?:
                throw UnknownAnonymousPartyException("Unknown $anonymousParty")
        val issuingCert = anonymousIdentity.certPath.certificates[1]
        require(issuingCert.publicKey == party.owningKey) {
            "Issuing certificate's public key must match the party key ${party.owningKey.toStringShort()}."
        }
    }
}
