// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 RTXwcz
package com.rtxwcz.crossband;

import android.content.Context;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

final class HelperClient {
    static synchronized String key(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "helper.key");
        if (!file.exists()) Files.write(file.toPath(), UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static String call(Context context, String command, String iface) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 37643), 1500);
            socket.setSoTimeout(5000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(key(context));
            output.writeUTF(command);
            if (command.equals("start")) output.writeUTF(iface);
            output.flush();
            return new DataInputStream(socket.getInputStream()).readUTF();
        }
    }
}
