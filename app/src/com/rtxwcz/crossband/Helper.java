// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 RTXwcz
package com.rtxwcz.crossband;

import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TetheringManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.lang.reflect.Field;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Runs only as the ADB shell. It handles this app's DIRECT-CB- groups only.
public final class Helper {
    private Context context;
    private TetheringManager tethering;
    private Object miui;
    private Class<?> miuiClass;
    private Object tap;
    private TetheringManager.TetheringRequest request;
    private String downstream;

    public static void main(String[] args) throws Exception {
        if (args[0].equals("stop")) {
            try (Socket socket = new Socket("127.0.0.1", 37643)) {
                socket.setSoTimeout(10000);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeUTF(args[1]);
                output.writeUTF("stop");
                output.flush();
                System.out.println(new DataInputStream(socket.getInputStream()).readUTF());
            }
            return;
        }
        Looper.prepareMainLooper();
        new Helper().run(args[0]);
        Looper.loop();
    }

    private void run(String key) throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context base = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        context = new ContextWrapper(base.createPackageContext("com.android.shell", 0)) {
            public String getOpPackageName() { return "com.android.shell"; }
            public String getPackageName() { return "com.android.shell"; }
            public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
        };
        tethering = (TetheringManager) context.getSystemService("tethering");
        Field managerContext = TetheringManager.class.getDeclaredField("mContext");
        managerContext.setAccessible(true);
        managerContext.set(tethering, context);
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "MiuiWifiService");
        miuiClass = Class.forName("android.net.wifi.IMiuiWifiManager");
        miui = Class.forName("android.net.wifi.IMiuiWifiManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        ServerSocket server = new ServerSocket(37643, 8, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> {
            while (true) {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    socket.setSoTimeout(5000);
                    if (!key.equals(input.readUTF())) { output.writeUTF("AUTH_ERROR"); continue; }
                    String command = input.readUTF();
                    try {
                        if (command.equals("start")) {
                            String iface = input.readUTF();
                            if (tap == null) {
                                try { start(iface); }
                                catch (Exception error) {
                                    try { stop(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                                    throw error;
                                }
                            }
                        } else if (command.equals("stop")) {
                            stop();
                        } else if (!command.equals("status")) {
                            throw new IllegalArgumentException("Unknown hotspot command");
                        }
                        output.writeUTF("OK");
                    } catch (Exception error) {
                        error.printStackTrace();
                        output.writeUTF("ERROR: " + error.getMessage());
                    }
                } catch (Exception error) { error.printStackTrace(); }
            }
        }, "CrossBandControl").start();
        System.out.println("CrossBand helper ready uid=" + android.os.Process.myUid());
    }

    private void start(String iface) throws Exception {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        Field connectivityContext = ConnectivityManager.class.getDeclaredField("mContext");
        connectivityContext.setAccessible(true);
        connectivityContext.set(connectivity, context);
        Network wifi = null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) wifi = network;
        }
        LinkProperties upstream = connectivity.getLinkProperties(wifi);
        // MIUI starts the native tether daemon and resets its DNS state.
        // Start the virtual tether afterwards so its upstream DNS update is last.
        boolean nat = (Boolean) miuiClass.getMethod("startP2ptethering", String.class, String.class)
                .invoke(miui, iface, upstream.getInterfaceName());
        if (!nat) throw new IllegalStateException("Miui P2P NAT rejected");
        downstream = iface;
        Object testNetworkManager = context.getSystemService("test_network");
        tap = testNetworkManager.getClass().getMethod("createTapInterface").invoke(testNetworkManager);
        String tapName = (String) tap.getClass().getMethod("getInterfaceName").invoke(tap);
        TetheringManager.TetheringRequest.Builder builder = new TetheringManager.TetheringRequest.Builder(7);
        builder.getClass().getMethod("setInterfaceName", String.class).invoke(builder, tapName);
        request = builder.build();
        CountDownLatch completed = new CountDownLatch(1);
        int[] result = {-1};
        tethering.startTethering(request, Runnable::run, new TetheringManager.StartTetheringCallback() {
            public void onTetheringStarted() { result[0] = 0; completed.countDown(); }
            public void onTetheringFailed(int error) { result[0] = error; completed.countDown(); }
        });
        if (!completed.await(4, TimeUnit.SECONDS) || result[0] != 0) {
            stop();
            throw new IllegalStateException("Virtual tethering start=" + result[0]);
        }
        System.out.println("sharing started " + iface + " -> " + upstream.getInterfaceName() + " dns=" + tapName);
    }

    private void stop() throws Exception {
        if (downstream != null) {
            miuiClass.getMethod("stopP2ptethering", String.class).invoke(miui, downstream);
            downstream = null;
        }
        if (tap != null) {
            CountDownLatch completed = new CountDownLatch(1);
            int[] result = {-1};
            try {
                if (request != null) {
                    tethering.stopTethering(request, Runnable::run, new TetheringManager.StopTetheringCallback() {
                        public void onStopTetheringSucceeded() { result[0] = 0; completed.countDown(); }
                        public void onStopTetheringFailed(int error) { result[0] = error; completed.countDown(); }
                    });
                    if (!completed.await(4, TimeUnit.SECONDS) || result[0] != 0)
                        throw new IllegalStateException("Virtual tethering stop=" + result[0]);
                }
            } finally {
                ((ParcelFileDescriptor) tap.getClass().getMethod("getFileDescriptor").invoke(tap)).close();
                tap = null;
                request = null;
            }
            System.out.println("sharing stopped");
        }
    }
}
