// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 RTXwcz
package com.rtxwcz.crossband;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.sun.security.x509.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

// ADB remains local; this class runs only the fixed hotspot helper bootstrap.
final class LocalAdb extends AbsAdbConnectionManager {
    private final PrivateKey privateKey;
    private final Certificate certificate;

    LocalAdb(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(10, TimeUnit.SECONDS);
        File keyFile = new File(context.getFilesDir(), "adb-private.der");
        File certFile = new File(context.getFilesDir(), "adb-cert.der");
        if (!keyFile.exists()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            java.security.KeyPair pair = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=CrossBandHotspot");
            String algorithm = "SHA256withRSA";
            X509CertInfo info = new X509CertInfo();
            info.set("version", new CertificateVersion(2));
            info.set("serialNumber", new CertificateSerialNumber(new SecureRandom().nextInt() & Integer.MAX_VALUE));
            info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
            info.set("subject", new CertificateSubjectName(subject));
            info.set("issuer", new CertificateIssuerName(subject));
            info.set("key", new CertificateX509Key(pair.getPublic()));
            info.set("validity", new CertificateValidity(new Date(System.currentTimeMillis() - 86400000L),
                    new Date(System.currentTimeMillis() + 315360000000L)));
            X509CertImpl cert = new X509CertImpl(info);
            cert.sign(pair.getPrivate(), algorithm);
            Files.write(certFile.toPath(), cert.getEncoded());
            Files.write(keyFile.toPath(), pair.getPrivate().getEncoded());
        }
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        try (java.io.InputStream input = Files.newInputStream(certFile.toPath())) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    protected PrivateKey getPrivateKey() { return privateKey; }
    protected Certificate getCertificate() { return certificate; }
    protected String getDeviceName() { return "CrossBandHotspot"; }

    String shell(String command) throws Exception {
        try (AdbStream stream = openStream("shell:sh")) {
            java.io.OutputStream output = stream.openOutputStream();
            output.write((command + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return new String(stream.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void enable(Context context) {
        Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
    }

    static void ensureHelper(Context context) throws Exception {
        try { if (HelperClient.call(context, "status", "").equals("OK")) return; }
        catch (java.io.IOException stopped) { /* A reboot ends the shell helper. */ }
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0)
            throw new IllegalStateException("请先点击“配置免电脑启动”完成一次配对");
        enable(context);
        try (LocalAdb adb = new LocalAdb(context)) {
            if (!adb.connectTls(context, 20000))
                throw new IllegalStateException("未发现本机无线调试，请在系统提示中允许此 Wi-Fi 网络");
            String apk = context.getApplicationInfo().sourceDir;
            adb.shell("cp -f '" + apk + "' /data/local/tmp/crossband.apk && chmod 444 /data/local/tmp/crossband.apk\n"
                    + "CLASSPATH=/data/local/tmp/crossband.apk nohup app_process /system/bin --nice-name=CrossBandHelper "
                    + "com.rtxwcz.crossband.Helper " + HelperClient.key(context)
                    + " </dev/null >/data/local/tmp/crossband-helper.log 2>&1 &\nsleep 1");
            if (!HelperClient.call(context, "status", "").equals("OK"))
                throw new IllegalStateException("共享后台未启动");
            android.util.Log.i("CrossBand", "helper restored by local ADB");
        }
    }
}
