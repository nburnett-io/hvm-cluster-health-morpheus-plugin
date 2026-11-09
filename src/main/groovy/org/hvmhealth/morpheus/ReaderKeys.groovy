package org.hvmhealth.morpheus

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Who may read /v1/clusters on the status URL: a tool whose public key (Ed25519) is in the Reader keys setting, and that signs each request with the private half.
 * The port is plain HTTP, so a token would cross it in clear and the plugin would have to keep it. A signature carries no secret, and the plugin holds only public keys.
 * Pure: no socket and no Morpheus. See docs/ALERTING.md.
 */
class ReaderKeys {
    static final String PATH = '/v1/clusters'
    static final String TIME = 'X-HH-Time'
    static final String SIGNATURE = 'X-HH-Signature'
    /** A request's time may be this far from this node's clock, either way. */
    static final long WINDOW_MS = 5L * 60 * 1000
    /** A refused read is tried against every key, so the setting holds only a few. */
    static final int MAX_KEYS = 20

    /** What is signed: hvm-health-v1, the method, the path and the time, a line each, with nothing after the time. */
    static String message(String method, String path, String time) {
        "hvm-health-v1\n${method}\n${path}\n${time}".toString()
    }

    /**
     * [keys: the public keys, bad: how many lines are not one]. One key a line, and the key is the line's last word, so a line may start with a label. Blank lines
     * and lines starting # are skipped. A line that is not an Ed25519 public key, and any key past MAX_KEYS, counts as bad and is never used.
     */
    static Map parse(String setting) {
        List<PublicKey> keys = []
        int bad = 0
        (setting ?: '').split(/\r?\n/)*.trim().findAll { String line -> line && !line.startsWith('#') }.each { String line ->
            PublicKey k = keys.size() < MAX_KEYS ? publicKey(line.split(/\s+/)[-1]) : null
            if (k) {
                keys << k
            } else {
                bad++
            }
        }
        [keys: keys, bad: bad]
    }

    /** The standard base64 of an X.509 SubjectPublicKeyInfo, as an Ed25519 key; null for anything else, an Ed448 key included. */
    private static PublicKey publicKey(String word) {
        try {
            KeyFactory.getInstance('Ed25519').generatePublic(new X509EncodedKeySpec(Base64.decoder.decode(word)))
        } catch (Exception ignored) {
            null
        }
    }

    /**
     * Null when one of {@code keys} signed this request; otherwise why not, in words for the log. A signature is the two headers in their form: a time of 1 to 12 digits,
     * in seconds, and standard base64 of 64 bytes. Anything else counts as none. Never throws.
     */
    static String refusal(List<PublicKey> keys, String method, String path, String time, String signature, long now) {
        try {
            if (!keys) {
                return 'no reader keys are set'
            }
            byte[] sig = signatureBytes(signature)
            if (time == null || !(time ==~ /\d{1,12}/) || sig == null) {
                return 'the request carries no signature'
            }
            long off = Math.abs((time as long) * 1000L - now)
            if (off > WINDOW_MS) {
                return "its time is ${(off + 999L).intdiv(1000L)} s from this node's clock".toString()
            }
            byte[] signed = message(method, path, time).getBytes('UTF-8')
            keys.any { PublicKey k -> verifies(k, signed, sig) } ? null : 'the signature matches none of the reader keys'
        } catch (Throwable ignored) {
            'the signature matches none of the reader keys'
        }
    }

    private static byte[] signatureBytes(String s) {
        if (!s) {
            return null
        }
        try {
            byte[] b = Base64.decoder.decode(s)
            b.length == 64 ? b : null
        } catch (IllegalArgumentException ignored) {
            null
        }
    }

    /** A new Signature for each key, as one is not safe to share between threads. */
    private static boolean verifies(PublicKey key, byte[] signed, byte[] sig) {
        try {
            Signature v = Signature.getInstance('Ed25519')
            v.initVerify(key)
            v.update(signed)
            v.verify(sig)
        } catch (Exception ignored) {
            false
        }
    }
}
