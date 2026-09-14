package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Turns a presented key into the machine it belongs to (ADR 0013). */
@Component
class ServiceCredentials {

    /**
     * The shape a key must have.
     *
     * <p>The prefix earns its keep twice: an operator can see at a glance that
     * they have pasted an OpsAtlas key rather than something else, and
     * {@code scripts/check-secrets.sh} can recognise one that has escaped into
     * source control. A bare hex string would be indistinguishable from a hash,
     * a commit id, or nothing at all.
     *
     * <p>{@code _sk_} rather than a bare {@code opsatlas_}, which was the first
     * attempt: every Prometheus metric this system exports is named
     * {@code opsatlas_something_long}, so the scanner flagged
     * {@code opsatlas_observer_observations_applied_total} as a leaked
     * credential. A check that cries wolf gets switched off, which is the one
     * outcome worse than not having it (ADR 0012).
     */
    static final String KEY_PREFIX = "opsatlas_sk_";

    static final Pattern KEY_SHAPE = Pattern.compile(KEY_PREFIX + "[A-Za-z0-9_-]{32,128}");

    static final String HEADER = "X-OpsAtlas-Key";

    private final ServiceCredentialRepository credentials;
    private final Clock clock;

    ServiceCredentials(ServiceCredentialRepository credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    /**
     * SHA-256, hex.
     *
     * <p>A fast hash, which would be indefensible for a password and is right
     * here. The key is 256 bits from a CSPRNG rather than something a human
     * chose, so there is no dictionary to run against it and a work factor would
     * buy nothing but latency on every request. What the hash is for is that a
     * database dump does not hand over the key itself.
     */
    static String hash(String key) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform and was not found", e);
        }
    }

    /**
     * The machine this key belongs to, if any.
     *
     * <p>Looked up by hash, so an invalid key and a valid one cost the same
     * single indexed query: there is no comparison loop here whose duration
     * could tell a caller they were getting closer.
     */
    @Transactional
    Optional<Principal> authenticate(String presented) {
        if (presented == null || !KEY_SHAPE.matcher(presented).matches()) {
            // Refused on shape before it reaches the database. A malformed key
            // is not a lookup, and this keeps arbitrary caller input out of a
            // query that runs before anything has authenticated.
            return Optional.empty();
        }

        return credentials.findBySecretHash(hash(presented)).map(credential -> {
            // "Can this key be deleted?" is the question nobody can answer about
            // a credential they are afraid to remove, so it is recorded.
            credential.usedAt(clock.instant());
            credentials.save(credential);
            return new Principal(
                    credential.getOrgId(),
                    // Reads as a machine in the audit log, because it is one.
                    "service:" + credential.getName(),
                    credential.getName() + " (service credential)");
        });
    }
}
