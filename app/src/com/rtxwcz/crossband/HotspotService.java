// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 RTXwcz
package com.rtxwcz.crossband;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.IBinder;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HotspotService extends Service {
    public static final String STATUS = "com.rtxwcz.crossband.STATUS";
    public static volatile String message = "热点未启动";
    public static volatile boolean running;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private boolean stopping;
    private boolean creating;
    private boolean destroying;
    private int operation;
    private static final ExecutorService control = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            boolean creatingAtEvent = creating;
            manager.requestGroupInfo(channel, group -> {
                if (destroying) return;
                if (stopping) { if (group == null) finishStop(operation); return; }
                if (group != null) showGroup(group);
                else if (!creatingAtEvent && !creating) {
                    notifyHelper(false, "", "");
                    if (!stopping && running) update("热点连接已结束", false);
                }
            });
        }
    };

    public void onCreate() {
        super.onCreate();
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), () -> { if (!destroying) update("Wi‑Fi 服务连接已断开", false); });
        registerReceiver(receiver, new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), Context.RECEIVER_EXPORTED);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("hotspot", "热点运行状态", NotificationManager.IMPORTANCE_LOW));
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        int current = ++operation;
        startForeground(1, notification("正在处理热点"));
        if ("stop".equals(intent.getAction())) {
            creating = false;
            stopping = true;
            update("正在停止热点…", true);
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                public void onSuccess() { removeForStop(current); }
                public void onFailure(int reason) { removeForStop(current); }
            });
            return START_NOT_STICKY;
        }
        stopping = false;
        creating = true;
        String name = intent.getStringExtra("name");
        String password = intent.getStringExtra("password");
        int band = intent.getIntExtra("band", 1);
        update("正在准备共享后台…", true);
        control.execute(() -> {
            try {
                LocalAdb.ensureHelper(this);
                getMainLooperHandler().post(() -> {
                    if (current == operation && !stopping && !destroying) createHotspot(current, name, password, band);
                });
            } catch (Exception error) {
                Log.e("CrossBand", "bootstrap", error);
                getMainLooperHandler().post(() -> {
                    if (current != operation || destroying) return;
                    update("启动未完成：" + error.getMessage(), false);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                });
            }
        });
        return START_NOT_STICKY;
    }

    private void createHotspot(int current, String name, String password, int band) {
        update("正在开启热点…", true);
        manager.requestGroupInfo(channel, group -> {
            if (current != operation || stopping || destroying) return;
            Runnable create = () -> {
                if (current != operation || stopping || destroying) return;
                WifiP2pConfig.Builder builder = new WifiP2pConfig.Builder().setNetworkName("DIRECT-CB-" + name)
                        .setPassphrase(password);
                int upstreamFrequency = ((WifiManager) getSystemService(WIFI_SERVICE)).getConnectionInfo().getFrequency();
                if (band == 2 && upstreamFrequency >= 4900 && upstreamFrequency < 5925)
                    builder.setGroupOperatingFrequency(upstreamFrequency);
                else builder.setGroupOperatingBand(band);
                manager.createGroup(channel, builder.build(), action(current, () ->
                        manager.requestGroupInfo(channel, active -> {
                            if (current == operation && !stopping && !destroying && active != null) showGroup(active);
                        })));
            };
            if (group == null) create.run();
            else manager.removeGroup(channel, action(current, create));
        });
    }

    private WifiP2pManager.ActionListener action(int current, Runnable success) {
        return new WifiP2pManager.ActionListener() {
            public void onSuccess() { if (current == operation && !destroying) success.run(); }
            public void onFailure(int reason) {
                if (current != operation || destroying) return;
                update("热点操作失败，错误码 " + reason, false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        };
    }

    private void removeForStop(int current) {
        if (current != operation || destroying) return;
        manager.requestGroupInfo(channel, group -> {
            if (current != operation || destroying) return;
            if (group == null) { finishStop(current); return; }
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                public void onSuccess() { checkStopped(current); }
                public void onFailure(int reason) {
                    manager.requestGroupInfo(channel, active -> {
                        if (current != operation || destroying) return;
                        if (active == null) finishStop(current);
                        else {
                            stopping = false;
                            update("停止未完成，错误码 " + reason + "。请再次点击停止热点。", true);
                        }
                    });
                }
            });
        });
    }

    private void checkStopped(int current) {
        if (current != operation || destroying) return;
        manager.requestGroupInfo(channel, group -> {
            if (group == null) finishStop(current);
        });
    }

    private void finishStop(int current) {
        if (current != operation || destroying) return;
        notifyHelper(false, "", "");
        update("热点已停止", false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void showGroup(WifiP2pGroup group) {
        creating = false;
        notifyHelper(true, group.getInterface(), group.getNetworkName());
        WifiInfo upstream = ((WifiManager) getSystemService(WIFI_SERVICE)).getConnectionInfo();
        int frequency = group.getFrequency();
        update("热点已开启\n" + group.getNetworkName() + "\n\n上游 Wi‑Fi：" + bandLabel(upstream.getFrequency())
                + "（" + upstream.getFrequency() + " MHz）\n热点频段：" + bandLabel(frequency)
                + "（" + frequency + " MHz）", true);
    }

    private String bandLabel(int frequency) {
        return frequency >= 4900 ? "5 GHz" : frequency >= 2400 ? "2.4 GHz" : "未连接";
    }

    private void notifyHelper(boolean active, String iface, String name) {
        control.execute(() -> {
            try {
                String result = HelperClient.call(this, active ? "start" : "stop", iface);
                Log.i("CrossBand", "helper " + (active ? "start " : "stop ") + result);
                if (!result.equals("OK")) getMainLooperHandler().post(() -> update(message + "\n网络共享：" + result, running));
            } catch (Exception error) {
                Log.e("CrossBand", "helper connection", error);
                if (active) getMainLooperHandler().post(() -> update(message + "\n共享连接中断，请重新启动热点", running));
            }
        });
    }

    private android.os.Handler getMainLooperHandler() { return new android.os.Handler(getMainLooper()); }

    private Notification notification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, HotspotService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "hotspot").setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("跨频热点").setContentText(text).setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).setOngoing(true).build();
    }

    private void update(String value, boolean active) {
        message = value;
        running = active;
        Log.i("CrossBand", value.replace('\n', ' '));
        getSystemService(NotificationManager.class).notify(1, notification(value.replace('\n', ' ')));
        sendBroadcast(new Intent(STATUS).setPackage(getPackageName()));
    }

    public IBinder onBind(Intent intent) { return null; }
    public void onDestroy() { destroying = true; ++operation; notifyHelper(false, "", ""); unregisterReceiver(receiver); channel.close(); running = false; super.onDestroy(); }
}
