// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 RTXwcz
package com.rtxwcz.crossband;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import io.github.muntashirakon.adb.android.AdbMdns;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class PairingService extends Service {
    static volatile String message = "等待系统配对窗口";
    private AdbMdns discovery;
    private volatile int port;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public void onCreate() {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "pairing", "免电脑启动配置", NotificationManager.IMPORTANCE_HIGH));
        discovery = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, value) -> {
            port = value;
            if (value > 0) show("已找到本机配对窗口，请在这里输入六位配对码");
        });
    }

    public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(2, new Notification.Builder(this, "pairing").setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("跨频热点正在配置").setContentText("请在配对通知中输入六位配对码").build());
        if ("code".equals(intent.getAction()) || "manual-code".equals(intent.getAction())) {
            String code = "manual-code".equals(intent.getAction()) ? intent.getStringExtra("code") :
                    RemoteInput.getResultsFromIntent(intent).getCharSequence("code").toString().trim();
            worker.execute(() -> pair(code));
        } else {
            discovery.start();
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == 0) LocalAdb.enable(this);
        }
        return START_NOT_STICKY;
    }

    private void pair(String code) {
        try (LocalAdb adb = new LocalAdb(this)) {
            if (port <= 0) throw new IllegalStateException("请先打开系统的“使用配对码配对设备”窗口");
            show("正在配对…");
            if (!adb.pair("127.0.0.1", port, code)) throw new IllegalStateException("配对码未通过");
            if (!adb.connectTls(this, 15000)) throw new IllegalStateException("配对成功，连接尚未就绪，请重新配置");
            String result = adb.shell("pm grant com.rtxwcz.crossband android.permission.WRITE_SECURE_SETTINGS");
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) throw new IllegalStateException(result);
            getSharedPreferences("hotspot", 0).edit().putBoolean("paired", true).apply();
            Log.i("CrossBand", "on-device pairing complete");
            show("配置完成，返回跨频热点即可启动；重启后同样直接使用");
            stopForeground(STOP_FOREGROUND_REMOVE);
            getSystemService(NotificationManager.class).notify(3, new Notification.Builder(this, "pairing")
                    .setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("跨频热点 · 配置完成")
                    .setContentText(message).setAutoCancel(true).build());
            stopSelf();
        } catch (Exception error) {
            Log.e("CrossBand", "pairing", error);
            show("配置未完成：" + error.getMessage());
        }
    }

    private Notification notification(String text) {
        Intent input = new Intent(this, PairingService.class).setAction("code");
        PendingIntent reply = PendingIntent.getForegroundService(this, 20, input, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action action = new Notification.Action.Builder(null, "输入配对码", reply)
                .addRemoteInput(new RemoteInput.Builder("code").setLabel("六位配对码").build()).build();
        return new Notification.Builder(this, "pairing").setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("跨频热点 · 免电脑配置").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).addAction(action)
                .setCategory(Notification.CATEGORY_MESSAGE).build();
    }
    private void show(String text) {
        message = text;
        getSystemService(NotificationManager.class).notify(3, notification(text));
        sendBroadcast(new Intent("com.rtxwcz.crossband.PAIRING_STATUS").setPackage(getPackageName()));
    }
    public IBinder onBind(Intent intent) { return null; }
    public void onDestroy() { discovery.stop(); worker.shutdown(); super.onDestroy(); }
}
