package com.glr.deenwallet.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal WebAuthn (passkey) verification that uses only the JDK and Jackson, so it needs no extra library.
 * Supports ES256 (-7) and RS256 (-257), attestation is not checked ("none"): the proof is the signature at every login.
 */
final class WebAuthnCore {

    static final int ES256 = -7;
    static final int RS256 = -257;
    private static final ObjectMapper JSON = new ObjectMapper();

    private WebAuthnCore() {}

    record Registered(byte[] credentialId, byte[] publicKeySpki, int alg, long signCount) {}

    static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] unb64u(String s) {
        if (s == null) throw new IllegalArgumentException("Invalid passkey data");
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid passkey data");
        }
    }

    // ---------------------------------------------------------------- registration

    static Registered verifyRegistration(String clientDataB64, String attestationObjectB64, byte[] expectedChallenge,
                                         String rpId, Set<String> origins) {
        checkClientData(unb64u(clientDataB64), "webauthn.create", expectedChallenge, origins);
        Object att = Cbor.decode(unb64u(attestationObjectB64));
        if (!(att instanceof Map<?, ?> m) || !(m.get("authData") instanceof byte[] authData)) {
            throw new IllegalArgumentException("Invalid passkey data");
        }
        Auth a = parseAuthData(authData, true);
        checkRpAndFlags(a, rpId);
        if (a.credentialId == null || a.credentialId.length == 0 || a.credentialId.length > 1023 || a.coseKey == null) {
            throw new IllegalArgumentException("The passkey has no key");
        }
        Object key = Cbor.decode(a.coseKey);
        if (!(key instanceof Map<?, ?> k)) throw new IllegalArgumentException("Invalid passkey key");
        long kty = num(k.get(1L)), alg = num(k.get(3L));
        PublicKey pk;
        try {
            if (kty == 2 && alg == ES256) {
                if (num(k.get(-1L)) != 1) throw new IllegalArgumentException("Unsupported passkey curve");
                byte[] x = (byte[]) k.get(-2L), y = (byte[]) k.get(-3L);
                AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
                ap.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
                pk = KeyFactory.getInstance("EC").generatePublic(
                        new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
            } else if (kty == 3 && alg == RS256) {
                byte[] n = (byte[]) k.get(-1L), e = (byte[]) k.get(-2L);
                pk = KeyFactory.getInstance("RSA").generatePublic(
                        new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
            } else {
                throw new IllegalArgumentException("Unsupported passkey type");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid passkey key");
        }
        return new Registered(a.credentialId, pk.getEncoded(), (int) alg, a.signCount);
    }

    // ---------------------------------------------------------------- login

    /** Returns the new sign counter. Throws IllegalArgumentException when anything is wrong. */
    static long verifyAssertion(String clientDataB64, String authDataB64, String signatureB64, byte[] expectedChallenge,
                                String rpId, Set<String> origins, byte[] spki, int alg, long storedCount) {
        byte[] clientData = unb64u(clientDataB64);
        checkClientData(clientData, "webauthn.get", expectedChallenge, origins);
        byte[] authData = unb64u(authDataB64);
        Auth a = parseAuthData(authData, false);
        checkRpAndFlags(a, rpId);
        if ((a.signCount != 0 || storedCount != 0) && a.signCount <= storedCount) {
            throw new IllegalArgumentException("Passkey counter went backwards");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(clientData);
            byte[] signed = new byte[authData.length + hash.length];
            System.arraycopy(authData, 0, signed, 0, authData.length);
            System.arraycopy(hash, 0, signed, authData.length, hash.length);
            PublicKey pk = KeyFactory.getInstance(alg == ES256 ? "EC" : "RSA").generatePublic(new X509EncodedKeySpec(spki));
            Signature s = Signature.getInstance(alg == ES256 ? "SHA256withECDSA" : "SHA256withRSA");
            s.initVerify(pk);
            s.update(signed);
            if (!s.verify(unb64u(signatureB64))) throw new IllegalArgumentException("Passkey signature is not valid");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Passkey signature is not valid");
        }
        return a.signCount;
    }

    // ---------------------------------------------------------------- helpers

    private static void checkClientData(byte[] clientData, String type, byte[] expectedChallenge, Set<String> origins) {
        JsonNode n;
        try {
            n = JSON.readTree(new String(clientData, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid passkey data");
        }
        if (n == null || !type.equals(n.path("type").asText(""))) throw new IllegalArgumentException("Invalid passkey data");
        byte[] ch = unb64u(n.path("challenge").asText(""));
        if (!MessageDigest.isEqual(ch, expectedChallenge)) throw new IllegalArgumentException("The passkey request expired. Try again.");
        String origin = n.path("origin").asText("");
        if (!origins.contains(origin)) {
            throw new IllegalArgumentException("This passkey was made for a different site ("
                    + (origin.length() > 90 ? origin.substring(0, 90) : origin) + ")");
        }
        if (n.path("crossOrigin").asBoolean(false)) throw new IllegalArgumentException("Invalid passkey data");
    }

    private static void checkRpAndFlags(Auth a, String rpId) {
        try {
            byte[] expected = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, a.rpIdHash)) throw new IllegalArgumentException("This passkey was made for a different site");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if ((a.flags & 0x01) == 0) throw new IllegalArgumentException("The passkey was not confirmed by you");
        if ((a.flags & 0x04) == 0) throw new IllegalArgumentException("Screen lock confirmation is required");
    }

    private static final class Auth {
        byte[] rpIdHash; int flags; long signCount; byte[] credentialId; byte[] coseKey;
    }

    private static Auth parseAuthData(byte[] d, boolean needAttested) {
        if (d.length < 37) throw new IllegalArgumentException("Invalid passkey data");
        Auth a = new Auth();
        a.rpIdHash = Arrays.copyOfRange(d, 0, 32);
        a.flags = d[32] & 0xff;
        a.signCount = ByteBuffer.wrap(d, 33, 4).getInt() & 0xffffffffL;
        if (needAttested) {
            if ((a.flags & 0x40) == 0 || d.length < 55) throw new IllegalArgumentException("The passkey has no key");
            int len = ((d[53] & 0xff) << 8) | (d[54] & 0xff);
            if (d.length < 55 + len) throw new IllegalArgumentException("Invalid passkey data");
            a.credentialId = Arrays.copyOfRange(d, 55, 55 + len);
            a.coseKey = Arrays.copyOfRange(d, 55 + len, d.length);
        }
        return a;
    }

    private static long num(Object o) {
        if (o instanceof Long l) return l;
        throw new IllegalArgumentException("Invalid passkey key");
    }

    /** Tiny CBOR reader: ints, byte/text strings, arrays, maps, simple values. Enough for WebAuthn. */
    static final class Cbor {
        private final byte[] b;
        private int p;

        private Cbor(byte[] b) { this.b = b; }

        static Object decode(byte[] data) {
            try {
                return new Cbor(data).item(0);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid passkey data");
            }
        }

        private long len(int info) {
            if (info < 24) return info;
            int n = info == 24 ? 1 : info == 25 ? 2 : info == 26 ? 4 : info == 27 ? 8 : -1;
            if (n < 0 || p + n > b.length) throw new IllegalArgumentException("Invalid passkey data");
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 8) | (b[p++] & 0xff);
            return v;
        }

        private Object item(int depth) {
            if (depth > 16 || p >= b.length) throw new IllegalArgumentException("Invalid passkey data");
            int ib = b[p++] & 0xff;
            int major = ib >> 5, info = ib & 31;
            switch (major) {
                case 0: return len(info);
                case 1: return -1 - len(info);
                case 2: case 3: {
                    long n = len(info);
                    if (n < 0 || n > b.length - p) throw new IllegalArgumentException("Invalid passkey data");
                    byte[] x = Arrays.copyOfRange(b, p, p + (int) n);
                    p += (int) n;
                    return major == 2 ? x : new String(x, StandardCharsets.UTF_8);
                }
                case 4: {
                    long n = len(info);
                    if (n < 0 || n > 1024) throw new IllegalArgumentException("Invalid passkey data");
                    List<Object> l = new ArrayList<>();
                    for (long i = 0; i < n; i++) l.add(item(depth + 1));
                    return l;
                }
                case 5: {
                    long n = len(info);
                    if (n < 0 || n > 1024) throw new IllegalArgumentException("Invalid passkey data");
                    Map<Object, Object> m = new LinkedHashMap<>();
                    for (long i = 0; i < n; i++) { Object k = item(depth + 1); m.put(k, item(depth + 1)); }
                    return m;
                }
                case 6: len(info); return item(depth + 1);
                default:
                    if (info == 20) return Boolean.FALSE;
                    if (info == 21) return Boolean.TRUE;
                    if (info == 22 || info == 23) return null;
                    if (info == 25) { p += 2; return 0L; }
                    if (info == 26) { p += 4; return 0L; }
                    if (info == 27) { p += 8; return 0L; }
                    throw new IllegalArgumentException("Invalid passkey data");
            }
        }
    }
}
