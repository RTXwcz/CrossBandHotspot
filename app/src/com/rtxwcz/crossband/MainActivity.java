// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 RTXwcz
package com.rtxwcz.crossband;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private EditText name, password;
    private Spinner band;
    private TextView status;
    private Button start, stop;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) { refresh(); }
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        try { HelperClient.key(this); }
        catch (Exception error) { throw new IllegalStateException(error); }
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : new String[]{Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS}) {
            if (checkSelfPermission(permission) != 0) missing.add(permission);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
        SharedPreferences prefs = getSharedPreferences("hotspot", 0);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(40), dp(28), dp(32));
        page.setBackgroundColor(Color.rgb(245, 247, 251));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            page.setPadding(dp(28), insets.getSystemWindowInsetTop() + dp(28), dp(28),
                    insets.getSystemWindowInsetBottom() + dp(24));
            return insets;
        });
        TextView title = text("跨频热点", 32, Color.rgb(20, 32, 52));
        title.setTypeface(null, Typeface.BOLD);
        page.addView(title);
        page.addView(text("连接 Wi‑Fi 的同时，共享你选择的频段", 16, Color.rgb(85, 98, 120)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(22));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(28);
        page.addView(card, cardParams);
        card.addView(text("热点名称", 14, Color.DKGRAY));
        name = field(prefs.getString("name", "Tablet"));
        card.addView(name);
        card.addView(text("显示为 DIRECT-CB- 加上你的名称", 12, Color.GRAY));
        card.addView(text("连接密码", 14, Color.DKGRAY));
        String savedPassword = prefs.getString("password", null);
        if (savedPassword == null) {
            savedPassword = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            prefs.edit().putString("password", savedPassword).apply();
        }
        password = field(savedPassword);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        card.addView(password);
        card.addView(text("热点频段", 14, Color.DKGRAY));
        band = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"自动", "2.4 GHz", "5 GHz"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        band.setAdapter(adapter);
        band.setSelection(prefs.getInt("band", 1));
        card.addView(band, new LinearLayout.LayoutParams(-1, dp(56)));
        card.addView(text("2.4 GHz 兼容更多设备；5 GHz 适合近距离高速连接。", 13, Color.GRAY));
        start = new Button(this);
        start.setText("启动热点");
        start.setOnClickListener(view -> startHotspot());
        card.addView(start, new LinearLayout.LayoutParams(-1, dp(56)));
        stop = new Button(this);
        stop.setText("停止热点");
        stop.setOnClickListener(view -> startService(new Intent(this, HotspotService.class).setAction("stop")));
        card.addView(stop, new LinearLayout.LayoutParams(-1, dp(56)));
        status = text("", 16, Color.rgb(35, 71, 125));
        status.setPadding(0, dp(24), 0, dp(16));
        page.addView(status);
        Button configure = new Button(this);
        boolean paired = prefs.getBoolean("paired", false);
        configure.setText(paired ? "免电脑启动已配置 · 重新配置" : "配置免电脑启动");
        configure.setOnClickListener(view -> {
            startActivity(new Intent(this, PairingActivity.class));
        });
        page.addView(configure);
        page.addView(text(paired ? "已保存本机配对。重启后，连接 Wi‑Fi，选择频段并启动即可。" :
                "首次配置：打开无线调试，点“使用配对码配对设备”，在通知中输入六位配对码。HyperOS 通知不显示输入按钮时，将设置与配对页面分屏或小窗显示，在应用内输入。", 13, Color.GRAY));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
        registerReceiver(receiver, new IntentFilter(HotspotService.STATUS), Context.RECEIVER_NOT_EXPORTED);
        refresh();
        handleCommand(getIntent());
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }

    private void handleCommand(Intent intent) {
        if (intent.hasExtra("band")) band.setSelection(intent.getIntExtra("band", 1));
        if ("start".equals(intent.getStringExtra("command"))) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == 0) startHotspot();
        }
        if ("stop".equals(intent.getStringExtra("command")))
            startService(new Intent(this, HotspotService.class).setAction("stop"));
    }

    private void startHotspot() {
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != 0) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 1);
            return;
        }
        String ssid = name.getText().toString().trim();
        String key = password.getText().toString();
        if (ssid.isEmpty() || ("DIRECT-CB-" + ssid).getBytes(StandardCharsets.UTF_8).length > 32) {
            Toast.makeText(this, "名称需为 1 至 22 个英文字节，中文占 3 个字节", Toast.LENGTH_LONG).show();
            return;
        }
        if (!key.matches("[\\x20-\\x7E]{8,63}")) {
            Toast.makeText(this, "密码需为 8 至 63 位英文、数字或符号", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("hotspot", 0).edit().putString("name", ssid).putString("password", key)
                .putInt("band", band.getSelectedItemPosition()).apply();
        startForegroundService(new Intent(this, HotspotService.class).setAction("start")
                .putExtra("name", ssid).putExtra("password", key)
                .putExtra("band", band.getSelectedItemPosition()));
    }

    private void refresh() {
        status.setText(HotspotService.message);
        start.setText(HotspotService.running ? "应用设置并重新启动" : "启动热点");
        stop.setEnabled(HotspotService.running);
    }

    private EditText field(String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(value);
        field.setTextSize(18);
        field.setPadding(0, dp(6), 0, dp(12));
        return field;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(0, dp(6), 0, dp(8));
        return text;
    }

    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value); }
    protected void onResume() { super.onResume(); if (status != null) refresh(); }
    protected void onDestroy() { unregisterReceiver(receiver); super.onDestroy(); }
}
