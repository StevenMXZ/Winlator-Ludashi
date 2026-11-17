package com.winlator.cmod.stream.moonlight;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.bouncycastle.jce.X509Principal;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Persistent GameStream host identity: RSA-2048 key, self-signed certificate, unique id and
 * the certificates of paired Moonlight clients. Stored in files/moonlight/.
 */
public final class HostIdentity {
    private static final String TAG = "LudashiMoonlight";

    public final String uniqueId;
    public final PrivateKey privateKey;
    public final X509Certificate certificate;
    /** PEM text of the server certificate, as sent in pairing ("plaincert"). */
    public final byte[] certPem;
    private final File clientsDir;

    private HostIdentity(String uniqueId, PrivateKey key, X509Certificate cert, byte[] certPem, File clientsDir) {
        this.uniqueId = uniqueId;
        this.privateKey = key;
        this.certificate = cert;
        this.certPem = certPem;
        this.clientsDir = clientsDir;
    }

    public static synchronized HostIdentity loadOrCreate(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "moonlight");
        File clients = new File(dir, "clients");
        clients.mkdirs();
        File keyFile = new File(dir, "host.key");
        File certFile = new File(dir, "host.crt");
        File idFile = new File(dir, "uniqueid");

        if (keyFile.isFile() && certFile.isFile() && idFile.isFile()) {
            try {
                byte[] keyDer = readFile(keyFile);
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
                byte[] pem = readFile(certFile);
                X509Certificate cert = parsePem(pem);
                String id = new String(readFile(idFile), StandardCharsets.US_ASCII).trim();
                return new HostIdentity(id, key, cert, pem, clients);
            } catch (Exception e) {
                Log.w(TAG, "stored identity unreadable, generating a new one", e);
            }
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        X509V3CertificateGenerator gen = new X509V3CertificateGenerator();
        X509Principal name = new X509Principal("CN=Ludashi Stream");
        long now = System.currentTimeMillis();
        gen.setSerialNumber(BigInteger.valueOf(now));
        gen.setIssuerDN(name);
        gen.setSubjectDN(name);
        gen.setNotBefore(new Date(now - 24L * 3600 * 1000));
        gen.setNotAfter(new Date(now + 20L * 365 * 24 * 3600 * 1000));
        gen.setPublicKey(kp.getPublic());
        gen.setSignatureAlgorithm("SHA256WithRSAEncryption");
        X509Certificate cert = gen.generate(kp.getPrivate(), new SecureRandom());

        byte[] pem = toPem(cert.getEncoded());
        byte[] idBytes = new byte[8];
        new SecureRandom().nextBytes(idBytes);
        String id = Hex.encode(idBytes).toUpperCase();

        writeFile(keyFile, kp.getPrivate().getEncoded());
        writeFile(certFile, pem);
        writeFile(idFile, id.getBytes(StandardCharsets.US_ASCII));
        Log.i(TAG, "generated new host identity " + id);
        return new HostIdentity(id, kp.getPrivate(), parsePem(pem), pem, clients);
    }

    /** True if this client certificate was paired before. */
    public synchronized boolean isPaired(X509Certificate clientCert) {
        if (clientCert == null) return false;
        return new File(clientsDir, fingerprint(clientCert) + ".pem").isFile();
    }

    public synchronized void addPairedClient(X509Certificate clientCert, String name) throws IOException {
        writeFile(new File(clientsDir, fingerprint(clientCert) + ".pem"), toPem(safeEncoded(clientCert)));
        Log.i(TAG, "paired client: " + name);
    }

    public synchronized List<X509Certificate> pairedClients() {
        List<X509Certificate> out = new ArrayList<>();
        File[] files = clientsDir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            try { out.add(parsePem(readFile(f))); } catch (Exception ignored) {}
        }
        return out;
    }

    public synchronized void unpairAll() {
        File[] files = clientsDir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    static String fingerprint(X509Certificate cert) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] safeEncoded(X509Certificate cert) {
        try { return cert.getEncoded(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static X509Certificate parsePem(byte[] pemOrDer) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pemOrDer));
    }

    static byte[] toPem(byte[] der) {
        String b64 = Base64.encodeToString(der, Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b64.length(); i += 64) sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
    }
}
