package com.miramwh.codingworld.client;

import com.miramwh.codingworld.api.ApiDocs;
import com.miramwh.codingworld.CodingWorldMod;
import com.miramwh.codingworld.net.ConsolePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Left: Python editor. Right: console / help. Centre: the world stays visible. */
public class CodeEditorScreen extends Screen {
    private static final Set<String> KEYWORDS = Set.of("and", "as", "assert", "break", "continue", "def", "del", "elif", "else",
            "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
            "raise", "return", "try", "while", "True", "False", "None");
    private static final Set<String> PY_BUILTINS = Set.of("print", "len", "range", "str", "int", "float", "bool", "list", "dict",
            "tuple", "set", "abs", "min", "max", "sum", "round", "sorted", "reversed", "enumerate", "zip", "map", "filter", "any",
            "all", "type", "isinstance", "chr", "ord", "divmod", "pow", "format", "repr", "exit", "math", "random", "time");
    private static final List<String> COMPLETIONS;

    static {
        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ApiDocs.Fn f : ApiDocs.FUNCTIONS) all.add(f.name());
        for (ApiDocs.Var v : ApiDocs.VARIABLES) all.add(v.name());
        all.addAll(KEYWORDS);
        all.addAll(PY_BUILTINS);
        for (String[] e : ApiDocs.EVENTS) all.add(e[1].substring(0, e[1].indexOf('(')));
        COMPLETIONS = new ArrayList<>(all);
    }

    private static final int C_BG = 0xE8141820, C_PANEL = 0xE81B2029, C_GUTTER = 0xE80F1218, C_BORDER = 0xFF3A4252;
    private static final int C_TEXT = 0xFFE6E6E6, C_KEYWORD = 0xFFC792EA, C_STRING = 0xFFECC48D, C_NUMBER = 0xFFB5E67E,
            C_COMMENT = 0xFF6A8A6A, C_FUNC = 0xFF82AAFF, C_VAR = 0xFF7FDBCA, C_BUILTIN = 0xFFFFCB6B, C_LINENO = 0xFF5C6370;

    private final List<String> lines = new ArrayList<>();
    private int cy = 0, cx = 0;
    private int ay = -1, ax = -1; // selection anchor
    private int scrollY = 0;
    private int scrollX = 0;
    private int consoleScroll = 0; // lines from the bottom
    private final List<String[]> undo = new ArrayList<>(), redo = new ArrayList<>();
    private long lastEditTime = 0;
    private int lastEditKind = 0;
    private boolean dirty = false;
    private long blinkStart = System.currentTimeMillis();

    // layout
    private int lx, ly, lw, lh; // left panel
    private int codeX, codeY, codeW, codeH, gutterW;
    private int rx, ry, rw, rh; // right panel
    private int lineH;

    private boolean showExamples = false;
    private boolean helpMode = false;
    private int helpScroll = 0;
    private EditBox helpSearch;
    private List<String> popup = new ArrayList<>();
    private int popupSel = 0;
    private boolean draggingSel = false;
    private boolean rotating = false;
    private final List<int[]> helpHits = new ArrayList<>(); // y1, y2, index into filtered
    private List<ApiDocs.Fn> helpFiltered = new ArrayList<>();
    private Button runButton, stopButton;

    public CodeEditorScreen() {
        super(Component.literal("Кодинг-мир"));
        String code = ClientState.code();
        lines.addAll(Arrays.asList(code.split("\n", -1)));
        if (lines.isEmpty()) lines.add("");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        lineH = font.lineHeight + 1;
        int m = 4;
        lw = Math.max(230, (int) (width * 0.40));
        rw = Math.max(170, (int) (width * 0.25));
        if (lw + rw + 3 * m > width) {
            lw = (width - 3 * m) * 6 / 10;
            rw = width - 3 * m - lw;
        }
        lx = m;
        ly = m;
        lh = height - 2 * m;
        rx = width - m - rw;
        ry = m;
        rh = height - 2 * m;
        gutterW = font.width("9999") + 8;
        codeX = lx + gutterW;
        codeY = ly + 26;
        codeW = lw - gutterW - 2;
        codeH = lh - 26 - 26;

        int bx = lx + 3;
        runButton = addRenderableWidget(Button.builder(Component.literal("▶ Запуск"), b -> runCode()).bounds(bx, ly + 3, 62, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Ctrl+Enter или F5"))).build());
        bx += 64;
        stopButton = addRenderableWidget(Button.builder(Component.literal("■ Стоп"), b -> ClientState.stop()).bounds(bx, ly + 3, 50, 20).build());
        bx += 52;
        addRenderableWidget(Button.builder(Component.literal("Примеры ▼"), b -> showExamples = !showExamples).bounds(bx, ly + 3, 64, 20).build());
        bx += 66;
        addRenderableWidget(Button.builder(Component.literal("Новый"), b -> {
            pushUndo();
            setText("# Мой код\n");
        }).bounds(bx, ly + 3, 40, 20).build());

        int hx = rx + rw - 3;
        hx -= 56;
        addRenderableWidget(Button.builder(Component.literal("Очистить"), b -> ClientState.clearConsole()).bounds(hx, ry + 3, 56, 20).build());
        hx -= 58;
        addRenderableWidget(Button.builder(Component.literal("Справка"), b -> {
            helpMode = !helpMode;
            helpSearch.visible = helpMode;
            if (helpMode) setFocused(helpSearch);
            else setFocused(null);
        }).bounds(hx, ry + 3, 56, 20).build());
        helpSearch = new EditBox(font, rx + 4, ry + 28, rw - 8, 16, Component.literal("поиск"));
        helpSearch.setHint(Component.literal("Поиск функции..."));
        helpSearch.visible = helpMode;
        helpSearch.setResponder(s -> helpScroll = 0);
        addRenderableWidget(helpSearch);
        setFocused(null);
    }

    // =================================================================
    // text helpers
    // =================================================================
    private String text() {
        return String.join("\n", lines);
    }

    private void setText(String t) {
        lines.clear();
        lines.addAll(Arrays.asList(t.split("\n", -1)));
        cy = 0;
        cx = 0;
        ay = -1;
        scrollY = 0;
        changed();
    }

    private void changed() {
        dirty = true;
        lastEditTime = System.currentTimeMillis();
        blinkStart = lastEditTime;
        ClientState.setCode(text());
    }

    private void pushUndo() {
        undo.add(new String[]{text(), String.valueOf(cy), String.valueOf(cx)});
        if (undo.size() > 300) undo.remove(0);
        redo.clear();
    }

    /** Groups typing into one undo step. kind: 1 typing, 2 deleting, 3 other. */
    private void beforeEdit(int kind) {
        long now = System.currentTimeMillis();
        if (kind == 3 || kind != lastEditKind || now - lastEditTime > 1200) pushUndo();
        lastEditKind = kind;
    }

    private void restore(String[] s) {
        lines.clear();
        lines.addAll(Arrays.asList(s[0].split("\n", -1)));
        cy = Math.min(Integer.parseInt(s[1]), lines.size() - 1);
        cx = Math.min(Integer.parseInt(s[2]), lines.get(cy).length());
        ay = -1;
        lastEditKind = 0;
        changed();
        ensureVisible();
    }

    private boolean hasSel() {
        return ay >= 0 && (ay != cy || ax != cx);
    }

    private int[] selRange() {
        if (ay < cy || (ay == cy && ax < cx)) return new int[]{ay, ax, cy, cx};
        return new int[]{cy, cx, ay, ax};
    }

    private String selectedText() {
        if (!hasSel()) return "";
        int[] s = selRange();
        if (s[0] == s[2]) return lines.get(s[0]).substring(s[1], s[3]);
        StringBuilder sb = new StringBuilder(lines.get(s[0]).substring(s[1]));
        for (int i = s[0] + 1; i < s[2]; i++) sb.append('\n').append(lines.get(i));
        sb.append('\n').append(lines.get(s[2]), 0, s[3]);
        return sb.toString();
    }

    private void deleteSel() {
        if (!hasSel()) {
            ay = -1;
            return;
        }
        int[] s = selRange();
        String head = lines.get(s[0]).substring(0, s[1]);
        String tail = lines.get(s[2]).substring(s[3]);
        for (int i = s[2]; i > s[0]; i--) lines.remove(i);
        lines.set(s[0], head + tail);
        cy = s[0];
        cx = s[1];
        ay = -1;
    }

    private void insert(String t) {
        deleteSel();
        String[] parts = t.replace("\r\n", "\n").replace("\t", "    ").split("\n", -1);
        String line = lines.get(cy);
        String head = line.substring(0, cx), tail = line.substring(cx);
        if (parts.length == 1) {
            lines.set(cy, head + parts[0] + tail);
            cx += parts[0].length();
        } else {
            lines.set(cy, head + parts[0]);
            for (int i = 1; i < parts.length; i++) lines.add(cy + i, parts[i]);
            cy += parts.length - 1;
            cx = parts[parts.length - 1].length();
            lines.set(cy, lines.get(cy) + tail);
        }
        changed();
        ensureVisible();
    }

    private static int indentOf(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private void moveTo(int y, int x, boolean select) {
        if (select) {
            if (ay < 0) {
                ay = cy;
                ax = cx;
            }
        } else {
            ay = -1;
        }
        cy = Math.max(0, Math.min(lines.size() - 1, y));
        cx = Math.max(0, Math.min(lines.get(cy).length(), x));
        blinkStart = System.currentTimeMillis();
        ensureVisible();
    }

    private void ensureVisible() {
        int visible = Math.max(1, codeH / lineH);
        if (cy < scrollY) scrollY = cy;
        if (cy >= scrollY + visible) scrollY = cy - visible + 1;
        int curX = font.width(lines.get(cy).substring(0, Math.min(cx, lines.get(cy).length())));
        int textW = codeW - 6;
        if (curX - scrollX > textW - 10) scrollX = curX - textW + 30;
        if (curX - scrollX < 0) scrollX = Math.max(0, curX - 30);
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private int wordLeft(String l, int x) {
        int i = x;
        while (i > 0 && !isIdent(l.charAt(i - 1))) i--;
        while (i > 0 && isIdent(l.charAt(i - 1))) i--;
        return i;
    }

    private int wordRight(String l, int x) {
        int i = x;
        while (i < l.length() && !isIdent(l.charAt(i))) i++;
        while (i < l.length() && isIdent(l.charAt(i))) i++;
        return i;
    }

    // =================================================================
    // actions
    // =================================================================
    private void runCode() {
        ClientState.setCode(text());
        ClientState.run(text());
        consoleScroll = 0;
        dirty = false;
    }

    private void updatePopup() {
        popup = new ArrayList<>();
        popupSel = 0;
        String l = lines.get(cy);
        int s = cx;
        while (s > 0 && isIdent(l.charAt(s - 1))) s--;
        String prefix = l.substring(s, cx);
        if (prefix.length() < 2 || Character.isDigit(prefix.charAt(0))) return;
        if (l.substring(0, s).contains("#")) return;
        String lp = prefix.toLowerCase(Locale.ROOT);
        for (String c : COMPLETIONS) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lp) && !c.equals(prefix)) popup.add(c);
            if (popup.size() >= 8) break;
        }
    }

    private void acceptPopup() {
        String choice = popup.get(popupSel);
        String l = lines.get(cy);
        int s = cx;
        while (s > 0 && isIdent(l.charAt(s - 1))) s--;
        beforeEdit(3);
        lines.set(cy, l.substring(0, s) + l.substring(cx));
        cx = s;
        ApiDocs.Fn f = ApiDocs.BY_NAME.get(choice);
        insert(f != null || PY_BUILTINS.contains(choice) && !Set.of("math", "random", "time").contains(choice) ? choice + "(" : choice);
        popup.clear();
    }

    private void toggleComment() {
        beforeEdit(3);
        int a = cy, b = cy;
        if (hasSel()) {
            int[] s = selRange();
            a = s[0];
            b = s[2];
        }
        boolean allCommented = true;
        for (int i = a; i <= b; i++) if (!lines.get(i).isBlank() && !lines.get(i).trim().startsWith("#")) allCommented = false;
        for (int i = a; i <= b; i++) {
            String l = lines.get(i);
            int ind = indentOf(l);
            if (allCommented) {
                String rest = l.substring(ind);
                if (rest.startsWith("# ")) rest = rest.substring(2);
                else if (rest.startsWith("#")) rest = rest.substring(1);
                lines.set(i, l.substring(0, ind) + rest);
            } else if (!l.isBlank()) {
                lines.set(i, l.substring(0, ind) + "# " + l.substring(ind));
            }
        }
        cx = Math.min(cx, lines.get(cy).length());
        if (ay >= 0) ax = Math.min(ax, lines.get(ay).length());
        changed();
    }

    private void indentLines(boolean out) {
        beforeEdit(3);
        int a = cy, b = cy;
        if (hasSel()) {
            int[] s = selRange();
            a = s[0];
            b = s[2];
        }
        for (int i = a; i <= b; i++) {
            String l = lines.get(i);
            if (out) {
                int n = Math.min(4, indentOf(l));
                lines.set(i, l.substring(n));
                if (i == cy) cx = Math.max(0, cx - n);
                if (i == ay) ax = Math.max(0, ax - n);
            } else {
                lines.set(i, "    " + l);
                if (i == cy) cx += 4;
                if (i == ay) ax += 4;
            }
        }
        changed();
    }

    // =================================================================
    // input
    // =================================================================
    private boolean charTyped0(char c, int mods) {
        if (helpMode && helpSearch.isFocused()) return helpSearch.charTyped(c, mods);
        if (c < 32 || c == 127) return false;
        beforeEdit(1);
        insert(String.valueOf(c));
        if (isIdent(c)) updatePopup();
        else popup.clear();
        return true;
    }

    private boolean keyPressed0(int key, int scan, int mods) {
        boolean ctrl = hasControlDown(), shift = hasShiftDown();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!popup.isEmpty()) {
                popup.clear();
                return true;
            }
            if (showExamples) {
                showExamples = false;
                return true;
            }
            onClose();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER && ctrl) || key == GLFW.GLFW_KEY_F5) {
            runCode();
            return true;
        }
        if (helpMode && helpSearch.isFocused()) {
            if (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_DOWN) {
                setFocused(null);
                return true;
            }
            return helpSearch.keyPressed(key, scan, mods);
        }
        if (!popup.isEmpty()) {
            if (key == GLFW.GLFW_KEY_DOWN) {
                popupSel = (popupSel + 1) % popup.size();
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP) {
                popupSel = (popupSel + popup.size() - 1) % popup.size();
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_ENTER) {
                acceptPopup();
                return true;
            }
        }
        String line = lines.get(cy);
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                beforeEdit(3);
                deleteSel();
                line = lines.get(cy);
                int ind = indentOf(line);
                String before = line.substring(0, cx).stripTrailing();
                int hashPos = before.indexOf('#');
                String code = hashPos >= 0 ? before.substring(0, hashPos).stripTrailing() : before;
                if (code.endsWith(":")) ind += 4;
                String trimmed = code.trim();
                if (trimmed.equals("return") || trimmed.startsWith("return ") || trimmed.equals("break") || trimmed.equals("continue") || trimmed.equals("pass"))
                    ind = Math.max(0, ind - 4);
                insert("\n" + " ".repeat(Math.min(ind, cx == 0 ? 0 : ind)));
                popup.clear();
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                beforeEdit(2);
                if (hasSel()) deleteSel();
                else if (ctrl) {
                    int w = wordLeft(line, cx);
                    lines.set(cy, line.substring(0, w) + line.substring(cx));
                    cx = w;
                } else if (cx > 0) {
                    String head = line.substring(0, cx);
                    int n = head.isBlank() && head.length() % 4 == 0 ? 4 : 1;
                    if (head.isBlank() && head.length() % 4 != 0) n = head.length() % 4;
                    lines.set(cy, line.substring(0, cx - n) + line.substring(cx));
                    cx -= n;
                } else if (cy > 0) {
                    int px = lines.get(cy - 1).length();
                    lines.set(cy - 1, lines.get(cy - 1) + line);
                    lines.remove(cy);
                    cy--;
                    cx = px;
                }
                changed();
                ensureVisible();
                updatePopup();
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                beforeEdit(2);
                if (hasSel()) deleteSel();
                else if (cx < line.length()) lines.set(cy, line.substring(0, cx) + line.substring(cx + 1));
                else if (cy < lines.size() - 1) {
                    lines.set(cy, line + lines.get(cy + 1));
                    lines.remove(cy + 1);
                }
                changed();
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                if (hasSel() || shift) indentLines(shift);
                else {
                    beforeEdit(1);
                    insert(" ".repeat(4 - cx % 4));
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                popup.clear();
                if (hasSel() && !shift) {
                    int[] s = selRange();
                    moveTo(s[0], s[1], false);
                } else if (ctrl) moveTo(cy, wordLeft(line, cx), shift);
                else if (cx > 0) moveTo(cy, cx - 1, shift);
                else if (cy > 0) moveTo(cy - 1, lines.get(cy - 1).length(), shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                popup.clear();
                if (hasSel() && !shift) {
                    int[] s = selRange();
                    moveTo(s[2], s[3], false);
                } else if (ctrl) moveTo(cy, wordRight(line, cx), shift);
                else if (cx < line.length()) moveTo(cy, cx + 1, shift);
                else if (cy < lines.size() - 1) moveTo(cy + 1, 0, shift);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (cy > 0) moveTo(cy - 1, columnAt(cy - 1, font.width(line.substring(0, cx))), shift);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (cy < lines.size() - 1) moveTo(cy + 1, columnAt(cy + 1, font.width(line.substring(0, cx))), shift);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                int ind = indentOf(line);
                moveTo(ctrl ? 0 : cy, ctrl ? 0 : (cx == ind ? 0 : ind), shift);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                moveTo(ctrl ? lines.size() - 1 : cy, ctrl ? lines.get(lines.size() - 1).length() : line.length(), shift);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                moveTo(cy - codeH / lineH, cx, shift);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                moveTo(cy + codeH / lineH, cx, shift);
                return true;
            }
            default -> { }
        }
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> {
                    ay = 0;
                    ax = 0;
                    cy = lines.size() - 1;
                    cx = lines.get(cy).length();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    if (hasSel()) Minecraft.getInstance().keyboardHandler.setClipboard(selectedText());
                    else Minecraft.getInstance().keyboardHandler.setClipboard(line + "\n");
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    beforeEdit(3);
                    if (hasSel()) {
                        Minecraft.getInstance().keyboardHandler.setClipboard(selectedText());
                        deleteSel();
                    } else {
                        Minecraft.getInstance().keyboardHandler.setClipboard(line + "\n");
                        if (lines.size() > 1) lines.remove(cy);
                        else lines.set(0, "");
                        cy = Math.min(cy, lines.size() - 1);
                        cx = 0;
                    }
                    changed();
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    beforeEdit(3);
                    insert(Minecraft.getInstance().keyboardHandler.getClipboard());
                    return true;
                }
                case GLFW.GLFW_KEY_Z -> {
                    if (shift) {
                        if (!redo.isEmpty()) {
                            undo.add(new String[]{text(), String.valueOf(cy), String.valueOf(cx)});
                            restore(redo.remove(redo.size() - 1));
                        }
                    } else if (!undo.isEmpty()) {
                        redo.add(new String[]{text(), String.valueOf(cy), String.valueOf(cx)});
                        restore(undo.remove(undo.size() - 1));
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_Y -> {
                    if (!redo.isEmpty()) {
                        undo.add(new String[]{text(), String.valueOf(cy), String.valueOf(cx)});
                        restore(redo.remove(redo.size() - 1));
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_D -> {
                    beforeEdit(3);
                    lines.add(cy + 1, line);
                    cy++;
                    changed();
                    return true;
                }
                case GLFW.GLFW_KEY_SLASH -> {
                    toggleComment();
                    return true;
                }
                case GLFW.GLFW_KEY_S -> {
                    ClientState.save();
                    dirty = false;
                    ClientState.add("Сохранено: " + ClientState.scriptFile(), ConsolePacket.SYSTEM);
                    return true;
                }
                default -> { }
            }
        }
        if (getFocused() != null && getFocused() != helpSearch) setFocused(null);
        return getFocused() == helpSearch && super.keyPressed(key, scan, mods);
    }

    private int columnAt(int row, int px) {
        String l = lines.get(row);
        int best = 0;
        for (int i = 0; i <= l.length(); i++) {
            int w = font.width(l.substring(0, i));
            if (w <= px + 1) best = i;
            else {
                int prev = font.width(l.substring(0, i - 1));
                if (px - prev > w - px) best = i;
                break;
            }
        }
        return best;
    }

    private boolean inCode(double mx, double my) {
        return mx >= lx && mx < lx + lw && my >= codeY && my < codeY + codeH;
    }

    private boolean inCentre(double mx, double my) {
        return mx > lx + lw + 4 && mx < rx - 4;
    }

    private boolean mouseClicked0(double mx, double my, int button) {
        if (showExamples) {
            List<Examples.Example> ex = Examples.all();
            int ex0 = lx + 3 + 116, ey = ly + 24;
            int w = 200;
            for (int i = 0; i < ex.size(); i++) {
                int y = ey + i * 12;
                if (mx >= ex0 && mx < ex0 + w && my >= y && my < y + 12) {
                    pushUndo();
                    setText(Examples.load(ex.get(i).file()));
                    ClientState.add("Загружен пример: " + ex.get(i).title() + ". Нажмите ▶ Запуск. (Ctrl+Z — вернуть свой код)", ConsolePacket.SYSTEM);
                    showExamples = false;
                    return true;
                }
            }
            showExamples = false;
            if (mx >= ex0 && mx < ex0 + w) return true;
        }
        if (!popup.isEmpty()) {
            int[] pp = popupPos();
            for (int i = 0; i < popup.size(); i++) {
                int y = pp[1] + i * 11;
                if (mx >= pp[0] && mx < pp[0] + pp[2] && my >= y && my < y + 11) {
                    popupSel = i;
                    acceptPopup();
                    return true;
                }
            }
            popup.clear();
        }
        if (super.mouseClicked(mx, my, button)) {
            // A clicked button must not keep keyboard focus, or Space/Enter while typing would press it again.
            if (getFocused() instanceof Button) setFocused(null);
            return true;
        }
        if (helpMode && mx >= rx && mx < rx + rw && my > ry + 48) {
            setFocused(null);
            for (int[] h : helpHits) {
                if (my >= h[0] && my < h[1]) {
                    ApiDocs.Fn f = helpFiltered.get(h[2]);
                    beforeEdit(3);
                    if (!lines.get(cy).isBlank()) {
                        cx = lines.get(cy).length();
                        insert("\n" + " ".repeat(indentOf(lines.get(cy))));
                    }
                    String ind = " ".repeat(indentOf(lines.get(cy)));
                    insert(f.example().replace("\n", "\n" + ind));
                    return true;
                }
            }
            return true;
        }
        if (inCode(mx, my) && button == 0) {
            setFocused(null);
            int row = Math.min(lines.size() - 1, scrollY + (int) ((my - codeY - 2) / lineH));
            int col = columnAt(Math.max(0, row), (int) mx - codeX - 3 + scrollX);
            moveTo(row, col, hasShiftDown());
            if (!hasShiftDown()) {
                ay = cy;
                ax = cx;
            }
            draggingSel = true;
            popup.clear();
            return true;
        }
        if (inCentre(mx, my)) {
            rotating = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingSel = false;
        rotating = false;
        return super.mouseReleased(mx, my, button);
    }

    private boolean mouseDragged0(double mx, double my, int button, double dx, double dy) {
        if (draggingSel) {
            int row = Math.max(0, Math.min(lines.size() - 1, scrollY + (int) Math.floor((my - codeY - 2) / lineH)));
            int col = columnAt(row, (int) mx - codeX - 3 + scrollX);
            cy = row;
            cx = col;
            ensureVisible();
            return true;
        }
        if (rotating && minecraft != null && minecraft.player != null) {
            double scale = minecraft.getWindow().getGuiScale();
            minecraft.player.turn(dx * scale * 1.5, dy * scale * 1.5);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    private boolean mouseScrolled0(double mx, double my, double delta) {
        if (mx >= lx && mx < lx + lw) {
            int visible = codeH / lineH;
            scrollY = Math.max(0, Math.min(Math.max(0, lines.size() - visible / 2), scrollY - (int) Math.signum(delta) * 3));
            return true;
        }
        if (mx >= rx && mx < rx + rw) {
            if (helpMode) helpScroll = Math.max(0, helpScroll - (int) Math.signum(delta) * 20);
            else consoleScroll = Math.max(0, consoleScroll + (int) Math.signum(delta) * 3);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void tick() {
        if (helpSearch != null) helpSearch.tick();
        if (dirty && System.currentTimeMillis() - lastEditTime > 1500) {
            ClientState.save();
            dirty = false;
        }
    }

    @Override
    public void removed() {
        ClientState.setCode(text());
        ClientState.save();
    }

    // =================================================================
    // rendering
    // =================================================================
    private void render0(GuiGraphics g, int mouseX, int mouseY, float partial) {
        runButton.active = true;
        stopButton.active = ClientState.running;
        renderLeft(g, mouseX, mouseY);
        renderRight(g, mouseX, mouseY);
        // hint in the centre
        int cxm = (lx + lw + rx) / 2;
        String hint = "Тяни мышью — поворот · Esc — закрыть";
        int hw = font.width(hint);
        if (hw < rx - lx - lw - 10) {
            g.fill(cxm - hw / 2 - 4, height - 18, cxm + hw / 2 + 4, height - 5, 0x88000000);
            g.drawString(font, hint, cxm - hw / 2, height - 15, 0xFFBBBBBB, false);
        }
        super.render(g, mouseX, mouseY, partial);
        if (showExamples) renderExamples(g, mouseX, mouseY);
        if (!popup.isEmpty()) renderPopup(g);
    }

    private void renderLeft(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(lx - 1, ly - 1, lx + lw + 1, ly + lh + 1, C_BORDER);
        g.fill(lx, ly, lx + lw, ly + lh, C_PANEL);
        g.fill(lx, codeY, lx + gutterW, codeY + codeH, C_GUTTER);
        g.fill(codeX, codeY, lx + lw, codeY + codeH, C_BG);
        // status
        String status = ClientState.running ? "● работает" : "○ ожидает";
        int sw = font.width(status);
        if (lx + 3 + 64 + 52 + 66 + 42 + sw + 6 < lx + lw)
            g.drawString(font, status, lx + lw - sw - 5, ly + 9, ClientState.running ? 0xFF55FF55 : 0xFF888888, false);

        int visible = codeH / lineH + 1;
        g.enableScissor(lx, codeY, lx + lw, codeY + codeH);
        int[] sel = hasSel() ? selRange() : null;
        boolean blink = ((System.currentTimeMillis() - blinkStart) / 500) % 2 == 0;
        for (int i = 0; i < visible; i++) {
            int row = scrollY + i;
            if (row >= lines.size()) break;
            int y = codeY + 2 + i * lineH;
            String l = lines.get(row);
            if (row == ClientState.errorLine - 1) g.fill(lx, y - 1, lx + lw, y + lineH - 1, 0x66FF3030);
            else if (row == cy) g.fill(codeX, y - 1, lx + lw, y + lineH - 1, 0x22FFFFFF);
            String num = String.valueOf(row + 1);
            g.drawString(font, num, lx + gutterW - 4 - font.width(num), y, row == ClientState.errorLine - 1 ? 0xFFFF6060 : C_LINENO, false);
            int tx = codeX + 3 - scrollX;
            if (sel != null && row >= sel[0] && row <= sel[2]) {
                int a = row == sel[0] ? sel[1] : 0, b = row == sel[2] ? sel[3] : l.length();
                int x1 = tx + font.width(l.substring(0, a)), x2 = tx + font.width(l.substring(0, b)) + (row < sel[2] ? 4 : 0);
                g.fill(Math.max(codeX, x1), y - 1, x2, y + lineH - 1, 0x883A6EA5);
            }
            g.enableScissor(codeX, codeY, lx + lw, codeY + codeH);
            drawHighlighted(g, l, tx, y);
            if (row == cy && blink && getFocused() == null) {
                int curX = tx + font.width(l.substring(0, Math.min(cx, l.length())));
                g.fill(curX, y - 1, curX + 1, y + lineH - 1, 0xFFFFFFFF);
            }
            g.disableScissor();
        }
        g.disableScissor();
        // scroll bar
        if (lines.size() > visible - 1) {
            int total = lines.size();
            int barH = Math.max(10, codeH * (visible - 1) / total);
            int barY = codeY + (codeH - barH) * scrollY / Math.max(1, total - visible + 1);
            g.fill(lx + lw - 3, Math.min(barY, codeY + codeH - barH), lx + lw - 1, Math.min(barY, codeY + codeH - barH) + barH, 0x88FFFFFF);
        }
        // hint line at the bottom
        int hy = codeY + codeH + 3;
        g.fill(lx, codeY + codeH, lx + lw, ly + lh, 0xFF11151C);
        ApiDocs.Fn fn = currentFunction();
        String pos = "стр " + (cy + 1) + ", кол " + (cx + 1);
        int pw = font.width(pos);
        if (fn != null) {
            String sig = fn.signature();
            g.drawString(font, font.plainSubstrByWidth(sig, lw - pw - 12), lx + 4, hy, C_BUILTIN, false);
            g.drawString(font, font.plainSubstrByWidth(fn.desc(), lw - 8), lx + 4, hy + 11, 0xFFAAAAAA, false);
        } else {
            g.drawString(font, font.plainSubstrByWidth("Ctrl+Enter — запуск · Tab — подсказка", lw - pw - 12), lx + 4, hy, 0xFF777777, false);
            g.drawString(font, font.plainSubstrByWidth("Файл: " + ClientState.scriptFile(), lw - 8), lx + 4, hy + 11, 0xFF555555, false);
        }
        g.drawString(font, pos, lx + lw - pw - 4, hy, 0xFF777777, false);
    }

    private void drawHighlighted(GuiGraphics g, String l, int x, int y) {
        int i = 0, n = l.length();
        while (i < n) {
            char c = l.charAt(i);
            int j = i + 1;
            int color = C_TEXT;
            if (c == '#') {
                j = n;
                color = C_COMMENT;
            } else if (c == '"' || c == '\'') {
                boolean triple = l.startsWith(String.valueOf(c).repeat(3), i);
                j = i + (triple ? 3 : 1);
                while (j < n) {
                    if (l.charAt(j) == '\\') {
                        j += 2;
                        continue;
                    }
                    if (triple ? l.startsWith(String.valueOf(c).repeat(3), j) : l.charAt(j) == c) {
                        j += triple ? 3 : 1;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                color = C_STRING;
                if (i > 0 && (l.charAt(i - 1) == 'f' || l.charAt(i - 1) == 'F')) color = 0xFFF0B070;
            } else if (Character.isDigit(c)) {
                while (j < n && (Character.isLetterOrDigit(l.charAt(j)) || l.charAt(j) == '.' || l.charAt(j) == '_')) j++;
                color = C_NUMBER;
            } else if (isIdent(c)) {
                while (j < n && isIdent(l.charAt(j))) j++;
                String w = l.substring(i, j);
                if (j < n && (l.charAt(j) == '"' || l.charAt(j) == '\'') && (w.equalsIgnoreCase("f") || w.equalsIgnoreCase("r"))) color = 0xFFF0B070;
                else if (KEYWORDS.contains(w)) color = C_KEYWORD;
                else if (ApiDocs.BY_NAME.containsKey(w)) color = C_FUNC;
                else if (PY_BUILTINS.contains(w)) color = C_BUILTIN;
                else if (isVar(w)) color = C_VAR;
                else if (j < n && l.charAt(j) == '(') color = 0xFFDDDDAA;
            } else {
                while (j < n && !isIdent(l.charAt(j)) && l.charAt(j) != '#' && l.charAt(j) != '"' && l.charAt(j) != '\'' && l.charAt(j) != ' ') j++;
                if ("()[]{}".indexOf(c) >= 0) color = 0xFFB0B0C0;
                else if (c != ' ') color = 0xFF89DDFF;
            }
            String part = l.substring(i, j);
            g.drawString(font, part, x, y, color, false);
            x += font.width(part);
            i = j;
        }
    }

    private static boolean isVar(String w) {
        for (ApiDocs.Var v : ApiDocs.VARIABLES) if (v.name().equals(w)) return true;
        return false;
    }

    /** The world function whose call the cursor is inside, or the one under the cursor. */
    private ApiDocs.Fn currentFunction() {
        String l = lines.get(cy);
        int depth = 0;
        for (int i = Math.min(cx, l.length()) - 1; i >= 0; i--) {
            char c = l.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) {
                    int e = i;
                    int s = e;
                    while (s > 0 && isIdent(l.charAt(s - 1))) s--;
                    ApiDocs.Fn f = ApiDocs.BY_NAME.get(l.substring(s, e));
                    if (f != null) return f;
                } else depth--;
            }
        }
        int s = Math.min(cx, l.length()), e = s;
        while (s > 0 && isIdent(l.charAt(s - 1))) s--;
        while (e < l.length() && isIdent(l.charAt(e))) e++;
        return ApiDocs.BY_NAME.get(l.substring(s, e));
    }

    private int[] popupPos() {
        String l = lines.get(cy);
        int x = codeX + 3 - scrollX + font.width(l.substring(0, Math.min(cx, l.length())));
        int y = codeY + 2 + (cy - scrollY + 1) * lineH;
        int w = 0;
        for (String p : popup) w = Math.max(w, font.width(p));
        w += 10;
        if (y + popup.size() * 11 > codeY + codeH) y = codeY + 2 + (cy - scrollY) * lineH - popup.size() * 11 - 1;
        return new int[]{Math.min(x, lx + lw - w), y, w};
    }

    private void renderPopup(GuiGraphics g) {
        int[] p = popupPos();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(p[0] - 1, p[1] - 1, p[0] + p[2] + 1, p[1] + popup.size() * 11 + 1, C_BORDER);
        g.fill(p[0], p[1], p[0] + p[2], p[1] + popup.size() * 11, 0xF0202530);
        for (int i = 0; i < popup.size(); i++) {
            int y = p[1] + i * 11;
            if (i == popupSel) g.fill(p[0], y, p[0] + p[2], y + 11, 0xFF3A6EA5);
            String s = popup.get(i);
            int col = ApiDocs.BY_NAME.containsKey(s) ? C_FUNC : isVar(s) ? C_VAR : KEYWORDS.contains(s) ? C_KEYWORD : C_TEXT;
            g.drawString(font, s, p[0] + 4, y + 2, col, false);
        }
        g.pose().popPose();
    }

    private void renderExamples(GuiGraphics g, int mx, int my) {
        List<Examples.Example> ex = Examples.all();
        int x = lx + 3 + 116, y = ly + 24, w = 200;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(x - 1, y - 1, x + w + 1, y + ex.size() * 12 + 1, C_BORDER);
        g.fill(x, y, x + w, y + ex.size() * 12, 0xF0202530);
        for (int i = 0; i < ex.size(); i++) {
            int yy = y + i * 12;
            if (mx >= x && mx < x + w && my >= yy && my < yy + 12) g.fill(x, yy, x + w, yy + 12, 0xFF3A6EA5);
            g.drawString(font, font.plainSubstrByWidth((i + 1) + ". " + ex.get(i).title(), w - 8), x + 4, yy + 2, C_TEXT, false);
        }
        g.pose().popPose();
    }

    private void renderRight(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(rx - 1, ry - 1, rx + rw + 1, ry + rh + 1, C_BORDER);
        g.fill(rx, ry, rx + rw, ry + rh, C_PANEL);
        g.drawString(font, helpMode ? "Справка" : "Консоль", rx + 6, ry + 9, 0xFFFFFFFF, false);
        int top = ry + 26;
        if (helpMode) {
            renderHelp(g, top + 22);
            return;
        }
        g.fill(rx, top, rx + rw, ry + rh, C_BG);
        int w = rw - 10;
        List<FormattedCharSequence> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (ClientState.Line l : ClientState.console) {
            int col = switch (l.type()) {
                case ConsolePacket.ERROR -> 0xFFFF6B6B;
                case ConsolePacket.SYSTEM -> 0xFF7FB8FF;
                case ConsolePacket.WARN -> 0xFFFFB86B;
                default -> 0xFFEAEAEA;
            };
            List<FormattedCharSequence> wrapped = font.split(FormattedText.of(l.text().isEmpty() ? " " : l.text()), w);
            for (FormattedCharSequence s : wrapped) {
                rows.add(s);
                colors.add(col);
            }
        }
        int visible = (ry + rh - top - 4) / lineH;
        int maxScroll = Math.max(0, rows.size() - visible);
        consoleScroll = Math.min(consoleScroll, maxScroll);
        int start = Math.max(0, rows.size() - visible - consoleScroll);
        g.enableScissor(rx, top, rx + rw, ry + rh);
        for (int i = 0; i < visible && start + i < rows.size(); i++) {
            g.drawString(font, rows.get(start + i), rx + 5, top + 3 + i * lineH, colors.get(start + i), false);
        }
        if (rows.isEmpty()) g.drawString(font, "Здесь будет вывод print() и ошибки", rx + 5, top + 3, 0xFF666666, false);
        g.disableScissor();
        if (consoleScroll > 0) g.drawString(font, "↓ ещё " + consoleScroll, rx + rw - 50, ry + rh - 11, 0xFFAAAAAA, false);
    }

    private void renderHelp(GuiGraphics g, int top) {
        g.fill(rx, top, rx + rw, ry + rh, C_BG);
        String q = helpSearch.getValue().trim().toLowerCase(Locale.ROOT);
        helpFiltered = new ArrayList<>();
        for (ApiDocs.Fn f : ApiDocs.FUNCTIONS)
            if (q.isEmpty() || f.name().toLowerCase(Locale.ROOT).contains(q) || f.desc().toLowerCase(Locale.ROOT).contains(q) || f.category().toLowerCase(Locale.ROOT).contains(q))
                helpFiltered.add(f);
        helpHits.clear();
        g.enableScissor(rx, top, rx + rw, ry + rh);
        int y = top + 3 - helpScroll;
        String cat = "";
        int w = rw - 10;
        for (int i = 0; i < helpFiltered.size(); i++) {
            ApiDocs.Fn f = helpFiltered.get(i);
            if (!f.category().equals(cat)) {
                cat = f.category();
                y += 3;
                g.drawString(font, "— " + cat + " —", rx + 5, y, 0xFFFFFFFF, false);
                y += lineH + 1;
            }
            int y0 = y;
            for (FormattedCharSequence s : font.split(FormattedText.of(f.signature()), w)) {
                g.drawString(font, s, rx + 5, y, C_FUNC, false);
                y += lineH;
            }
            for (FormattedCharSequence s : font.split(FormattedText.of(f.desc()), w - 6)) {
                g.drawString(font, s, rx + 11, y, 0xFFAAAAAA, false);
                y += lineH;
            }
            y += 3;
            helpHits.add(new int[]{y0, y, i});
        }
        if (q.isEmpty() || "переменные".contains(q)) {
            y += 3;
            g.drawString(font, "— Переменные —", rx + 5, y, 0xFFFFFFFF, false);
            y += lineH + 1;
            for (ApiDocs.Var v : ApiDocs.VARIABLES) {
                for (FormattedCharSequence s : font.split(FormattedText.of(v.name() + " — " + v.desc()), w)) {
                    g.drawString(font, s, rx + 5, y, C_VAR, false);
                    y += lineH;
                }
            }
            y += 3;
            g.drawString(font, "— События (def ...) —", rx + 5, y, 0xFFFFFFFF, false);
            y += lineH + 1;
            for (String[] e : ApiDocs.EVENTS) {
                for (FormattedCharSequence s : font.split(FormattedText.of(e[1] + " — " + e[2]), w)) {
                    g.drawString(font, s, rx + 5, y, C_BUILTIN, false);
                    y += lineH;
                }
            }
        }
        g.disableScissor();
        int contentH = y + helpScroll - top;
        helpScroll = Math.min(helpScroll, Math.max(0, contentH - (ry + rh - top) + 10));
        g.fill(rx, ry + rh - 13, rx + rw, ry + rh, 0xFF11151C);
        g.drawString(font, "клик — вставить пример в код", rx + rw - 5 - font.width("клик — вставить пример в код"), ry + rh - 11, 0xFF888888, false);
    }

    // Any editor bug must never take the whole game down: log it, repair the state and carry on.
    private void sanitize() {
        if (lines.isEmpty()) lines.add("");
        cy = Math.max(0, Math.min(cy, lines.size() - 1));
        cx = Math.max(0, Math.min(cx, lines.get(cy).length()));
        if (ay >= lines.size()) ay = -1;
        if (ay >= 0) ax = Math.max(0, Math.min(ax, lines.get(ay).length()));
        scrollY = Math.max(0, Math.min(scrollY, lines.size() - 1));
        if (popupSel >= popup.size()) popupSel = 0;
    }

    private void editorError(Throwable e) {
        CodingWorldMod.LOGGER.error("Code editor error", e);
        try {
            popup.clear();
            ay = -1;
            sanitize();
        } catch (Throwable ignored) {
            lines.clear();
            lines.add("");
            cy = cx = scrollY = 0;
        }
        if (System.currentTimeMillis() - lastErrorReport > 3000) {
            lastErrorReport = System.currentTimeMillis();
            ClientState.add("Ошибка редактора (игра продолжает работать): " + e, ConsolePacket.WARN);
        }
    }

    private long lastErrorReport;

    @Override
    public boolean charTyped(char c, int mods) {
        try { sanitize(); return charTyped0(c, mods); } catch (Throwable e) { editorError(e); return true; }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        try { sanitize(); return keyPressed0(key, scan, mods); } catch (Throwable e) { editorError(e); return true; }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        try { sanitize(); return mouseClicked0(mx, my, button); } catch (Throwable e) { editorError(e); return true; }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        try { sanitize(); return mouseDragged0(mx, my, button, dx, dy); } catch (Throwable e) { editorError(e); return true; }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        try { sanitize(); return mouseScrolled0(mx, my, delta); } catch (Throwable e) { editorError(e); return true; }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        try {
            sanitize();
            render0(g, mouseX, mouseY, partial);
        } catch (Throwable e) {
            com.mojang.blaze3d.systems.RenderSystem.disableScissor();
            editorError(e);
        }
    }
}
