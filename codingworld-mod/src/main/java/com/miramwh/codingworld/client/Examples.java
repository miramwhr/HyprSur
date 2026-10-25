package com.miramwh.codingworld.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Example programs bundled in the jar (assets/codingworld/examples). */
public final class Examples {
    public record Example(String file, String title) {}

    private static List<Example> list;

    private Examples() {}

    public static List<Example> all() {
        if (list == null) {
            list = new ArrayList<>();
            for (String l : load("index.txt").split("\n")) {
                int bar = l.indexOf('|');
                if (bar > 0) list.add(new Example(l.substring(0, bar).trim(), l.substring(bar + 1).trim()));
            }
        }
        return list;
    }

    public static String load(String file) {
        try (InputStream in = Examples.class.getResourceAsStream("/assets/codingworld/examples/" + file)) {
            if (in == null) return "# пример не найден: " + file + "\n";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            return "# не удалось загрузить пример\n";
        }
    }
}
