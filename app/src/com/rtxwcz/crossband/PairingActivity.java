// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 RTXwcz
package com.rtxwcz.crossband;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;

public final class PairingActivity extends Activity {
    private TextView status;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) { status.setText(PairingService.message); }
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding * 2, padding, padding);
        TextView title = new TextView(this);
        title.setText("免电脑启动 · 本机配对");
        title.setTextSize(24);
        page.addView(title);
        TextView help = new TextView(this);
        help.setText("把系统设置与此页面分屏或小窗显示。保持系统的配对窗口打开，在下方输入六位配对码。只需配置一次。");
        help.setTextSize(16);
        page.addView(help);
        Button settings = new Button(this);
        settings.setText("打开系统无线调试设置");
        settings.setOnClickListener(view -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(":settings:fragment_args_key", "toggle_adb_wireless")));
        page.addView(settings);
        EditText code = new EditText(this);
        code.setHint("六位配对码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        page.addView(code);
        Button pair = new Button(this);
        pair.setText("配对并保存");
        pair.setOnClickListener(view -> startForegroundService(new Intent(this, PairingService.class)
                .setAction("manual-code").putExtra("code", code.getText().toString().trim())));
        page.addView(pair);
        status = new TextView(this);
        status.setText(PairingService.message);
        status.setTextSize(16);
        page.addView(status);
        Button back = new Button(this);
        back.setText("返回热点");
        back.setOnClickListener(view -> {
            stopService(new Intent(this, PairingService.class));
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        });
        page.addView(back);
        setContentView(page);
        registerReceiver(receiver, new IntentFilter("com.rtxwcz.crossband.PAIRING_STATUS"), Context.RECEIVER_NOT_EXPORTED);
        startForegroundService(new Intent(this, PairingService.class).setAction("discover"));
    }
    public void onDestroy() { unregisterReceiver(receiver); super.onDestroy(); }
}
