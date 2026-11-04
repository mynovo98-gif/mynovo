package com.mynovo.mynovo1;

import android.content.Context;
import android.os.Build;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * ตัวจัดการ ADB (singleton) — สร้าง/เก็บ key ไว้ใน filesDir
 * key เดิมต้องคงอยู่ เพื่อไม่ต้อง pair ใหม่ทุกครั้ง
 */
public class AdbManager extends AbsAdbConnectionManager {

    // libadb-android 3.x ต้องใช้ Conscrypt ตอน pair (ต้องลงทะเบียนก่อนใช้งานอะไรทั้งหมด)
    static {
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
    }

    private static AdbManager instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static synchronized AdbManager get(Context ctx) throws Exception {
        if (instance == null) instance = new AdbManager(ctx.getApplicationContext());
        return instance;
    }

    private AdbManager(Context ctx) throws Exception {
        setApi(Build.VERSION.SDK_INT);

        File keyFile = new File(ctx.getFilesDir(), "adb_key.pk8");
        File certFile = new File(ctx.getFilesDir(), "adb_cert.der");

        PrivateKey pk = null;
        Certificate cert = null;

        if (keyFile.exists() && certFile.exists()) {
            try {
                pk = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(read(keyFile)));
                cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(read(certFile)));
            } catch (Exception e) {
                pk = null;
                cert = null;
            }
        }

        if (pk == null || cert == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();

            X500Name name = new X500Name("CN=BlueMousePro");
            long now = System.currentTimeMillis();
            ContentSigner signer = new JcaContentSignerBuilder("SHA512withRSA")
                    .build(kp.getPrivate());
            cert = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(
                            name,
                            BigInteger.valueOf(now),
                            new Date(now - 24L * 3600 * 1000),
                            new Date(now + 20L * 365 * 24 * 3600 * 1000),
                            name,
                            kp.getPublic()).build(signer));
            pk = kp.getPrivate();

            write(keyFile, pk.getEncoded());
            write(certFile, cert.getEncoded());
        }

        this.privateKey = pk;
        this.certificate = cert;
    }

    @Override public PrivateKey getPrivateKey() { return privateKey; }

    @Override public Certificate getCertificate() { return certificate; }

    @Override public String getDeviceName() { return "BlueMousePro"; }

    private static byte[] read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
