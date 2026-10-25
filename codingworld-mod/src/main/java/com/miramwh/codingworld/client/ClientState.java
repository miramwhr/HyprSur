package com.miramwh.codingworld.client;

import com.miramwh.codingworld.CodingWorldMod;
import com.miramwh.codingworld.net.ConsolePacket;
import com.miramwh.codingworld.net.Network;
import com.miramwh.codingworld.net.OpenEditorPacket;
import com.miramwh.codingworld.net.RunPacket;
import com.miramwh.codingworld.net.StatusPacket;
import com.miramwh.codingworld.net.StopPacket;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Client-side state shared by the editor screen and the HUD overlay. */
public final class ClientState {
    public static final int MAX_LINES = 1000;

    public record Line(String text, int type, long time) {}

    public static final List<Line> console = new ArrayList<>();
    public static int consoleVersion = 0;
    public static boolean running = false;
    public static boolean wantsKeys = false;
    public static int errorLine = 0;
    public static boolean pendingOpen = false;
    public static boolean inCodingWorld = false;
    private static String code;

    private ClientState() {}

    public static Path scriptFile() {
        return FMLPaths.GAMEDIR.get().resolve("codingworld").resolve("main.py");
    }

    public static String code() {
        if (code == null) {
            try {
                Path f = scriptFile();
                code = Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : Examples.load("welcome.py");
            } catch (IOException e) {
                code = Examples.load("welcome.py");
            }
        }
        return code;
    }

    public static void setCode(String c) {
        code = c;
    }

    public static void save() {
        if (code == null) return;
        try {
            Path f = scriptFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            CodingWorldMod.LOGGER.warn("Could not save script", e);
        }
    }

    public static void add(String text, int type) {
        for (String part : text.split("\n", -1)) console.add(new Line(part, type, System.currentTimeMillis()));
        while (console.size() > MAX_LINES) console.remove(0);
        consoleVersion++;
    }

    public static void clearConsole() {
        console.clear();
        consoleVersion++;
    }

    public static void run(String source) {
        save();
        errorLine = 0;
        int total = Math.max(1, (source.length() + RunPacket.CHUNK - 1) / RunPacket.CHUNK);
        for (int i = 0; i < total; i++) {
            String part = source.substring(i * RunPacket.CHUNK, Math.min(source.length(), (i + 1) * RunPacket.CHUNK));
            Network.toServer(new RunPacket(i, total, part));
        }
        running = true;
    }

    public static void stop() {
        Network.toServer(new StopPacket());
    }

    public static void onConsole(ConsolePacket p) {
        for (ConsolePacket.Line l : p.lines) {
            if (l.type() == ConsolePacket.CLEAR) {
                clearConsole();
                continue;
            }
            add(l.text(), l.type());
            if (l.type() == ConsolePacket.ERROR && l.line() > 0) errorLine = l.line();
        }
    }

    public static void onStatus(StatusPacket p) {
        running = p.running;
        wantsKeys = p.wantsKeys;
    }

    public static void onOpenEditor(OpenEditorPacket p) {
        inCodingWorld = true;
        pendingOpen = true;
    }

    public static void reset() {
        running = false;
        wantsKeys = false;
        inCodingWorld = false;
        pendingOpen = false;
        errorLine = 0;
    }
}
