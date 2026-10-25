package com.miramwh.codingworld.client;

import com.miramwh.codingworld.api.ApiDocs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The "Code Teach" book: everything about the mod, built from {@link ApiDocs} and the bundled examples. */
public class CodeTeachScreen extends BookViewScreen {
    private static final int WIDTH = 114, LINES = 14;
    private static List<FormattedText> cached;

    private final Screen parent;

    private CodeTeachScreen(Screen parent, List<FormattedText> pages) {
        super(new BookAccess() {
            @Override
            public int getPageCount() {
                return pages.size();
            }

            @Override
            public FormattedText getPageRaw(int i) {
                return i >= 0 && i < pages.size() ? pages.get(i) : FormattedText.EMPTY;
            }
        });
        this.parent = parent;
    }

    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (cached == null) cached = build(mc.font);
        mc.setScreen(new CodeTeachScreen(parent, cached));
    }

    @Override
    protected void createMenuControls() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, 196, 200, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------
    // building the pages
    // ------------------------------------------------------------------
    private static final class Pager {
        final Font font;
        final List<FormattedText> pages = new ArrayList<>();
        MutableComponent page = null;
        int used = 0;

        Pager(Font font) {
            this.font = font;
        }

        int height(Component c) {
            return Math.max(1, font.split(c, WIDTH).size());
        }

        void newPage() {
            if (page != null && used > 0) pages.add(page);
            page = Component.empty();
            used = 0;
        }

        void line(Component c) {
            int h = height(c);
            if (page == null || used + h > LINES) newPage();
            if (used > 0) page.append("\n");
            page.append(c);
            used += h;
        }

        /** A block of text kept on one page when it fits; split by lines otherwise. */
        void block(String text) {
            String[] rows = text.split("\n", -1);
            int h = 0;
            for (String r : rows) h += height(Component.literal(r));
            if (page != null && used > 0 && used + 1 + h <= LINES) {
                line(Component.empty());
            } else if (page == null || used > 0) {
                newPage();
            }
            for (String r : rows) line(Component.literal(r));
        }

        int pageNumber() {
            return pages.size() + (page != null && used > 0 ? 1 : 0);
        }

        List<FormattedText> finish() {
            if (page != null && used > 0) pages.add(page);
            return pages;
        }
    }

    private static List<FormattedText> build(Font font) {
        Map<String, List<ApiDocs.Fn>> byCat = new LinkedHashMap<>();
        for (ApiDocs.Fn f : ApiDocs.FUNCTIONS) byCat.computeIfAbsent(f.category(), k -> new ArrayList<>()).add(f);

        List<String> sections = new ArrayList<>();
        sections.add("Как играть");
        sections.add("Python");
        sections.addAll(byCat.keySet());
        sections.add("Переменные");
        sections.add("События");
        sections.add("Примеры");

        // pass 1: the body, remembering where each section starts
        Pager body = new Pager(font);
        Map<String, Integer> start = new LinkedHashMap<>();

        start.put("Как играть", body.pageNumber() + 1);
        body.newPage();
        body.block("§4§lКак играть§r\n\nСлева редактор кода, справа консоль, в центре мир.\n\n§1Ctrl+Enter§0 — запуск\n§1■ Стоп§0 — остановить\n§1Esc§0 — закрыть редактор\n§1K§0 — открыть снова\n§1Tab§0 — подсказка");
        body.block("Земля на высоте §1Y = 0§0, центр мира — точка §1(0, 0, 0)§0. Целые координаты — центр блока.");
        body.block("Ошибки видны в консоли с номером строки, а строка подсвечивается красным.");

        start.put("Python", body.pageNumber() + 1);
        body.newPage();
        body.block("§4§lPython§r\n\n§1if / elif / else§0\n§1for i in range(10):§0\n§1while x < 5:§0\n§1def имя(a, b=1):§0\n§1lambda x: x * 2§0\n§1try / except§0\nlist, dict, set, tuple, f\"строки {x}\", срезы, import math / random / time");
        body.block("§lУсловия:§r\n§8if PlayerX == 25:\n    Say(\"Ты на 25!\")\nelif IsNight:\n    SummonMob(0, 0, 0, \"zombie\")\nelse:\n    print(\"день\")");

        for (var e : byCat.entrySet()) {
            start.put(e.getKey(), body.pageNumber() + 1);
            body.newPage();
            body.block("§4§l" + e.getKey() + "§r\n§7функций: " + e.getValue().size());
            for (ApiDocs.Fn f : e.getValue())
                body.block("§1§l" + f.name() + "§r§1(" + f.params() + ")§0\n" + f.desc() + "\n§8" + f.example().replace("\n", "\n§8"));
        }

        start.put("Переменные", body.pageNumber() + 1);
        body.newPage();
        body.block("§4§lПеременные§r\n§7всегда свежие, их можно читать в любом месте");
        for (ApiDocs.Var v : ApiDocs.VARIABLES) body.block("§1§l" + v.name() + "§r\n" + v.desc());

        start.put("События", body.pageNumber() + 1);
        body.newPage();
        body.block("§4§lСобытия§r\n§7объяви функцию с таким именем");
        for (String[] ev : ApiDocs.EVENTS) body.block("§1§ldef " + ev[1] + ":§r\n" + ev[2]);

        start.put("Примеры", body.pageNumber() + 1);
        for (Examples.Example ex : Examples.all()) {
            body.newPage();
            body.block("§4§l" + ex.title() + "§r\n§7" + ex.file());
            body.block("§8" + Examples.load(ex.file()).stripTrailing().replace("\n", "\n§8"));
        }
        List<FormattedText> bodyPages = body.finish();

        // pass 2: title and contents with clickable page numbers
        int tocPages = toc(font, sections, start, 0).size();
        List<FormattedText> pages = new ArrayList<>(toc(font, sections, start, tocPages));
        pages.addAll(bodyPages);
        return pages;
    }

    private static List<FormattedText> toc(Font font, List<String> sections, Map<String, Integer> start, int offset) {
        Pager p = new Pager(font);
        p.newPage();
        p.line(Component.literal("§4§lCode Teach"));
        p.line(Component.literal("§7книга Кодинг-мира"));
        p.line(Component.empty());
        p.line(Component.literal("§0Нажми на раздел:"));
        for (String s : sections) {
            int page = start.get(s) + offset;
            p.line(Component.literal("§1" + page + "§0 · " + s)
                    .withStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.CHANGE_PAGE, String.valueOf(page)))));
        }
        return p.finish();
    }
}
