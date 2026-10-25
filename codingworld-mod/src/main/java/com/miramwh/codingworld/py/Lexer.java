package com.miramwh.codingworld.py;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Turns Python source into tokens, including INDENT/DEDENT for blocks. */
public final class Lexer {
    public enum T { NAME, NUMBER, STRING, FSTRING, OP, NEWLINE, INDENT, DEDENT, EOF }

    public static final class Token {
        public final T type;
        public final String text;
        public final Object value;
        public final int line;

        Token(T type, String text, Object value, int line) {
            this.type = type;
            this.text = text;
            this.value = value;
            this.line = line;
        }

        public boolean is(String op) {
            return (type == T.OP || type == T.NAME) && text.equals(op);
        }

        @Override
        public String toString() {
            return switch (type) {
                case NEWLINE -> "конец строки";
                case INDENT -> "отступ";
                case DEDENT -> "конец блока";
                case EOF -> "конец программы";
                default -> "'" + text + "'";
            };
        }
    }

    private static final String[] OPS3 = {"**=", "//=", "...", ">>=", "<<="};
    private static final String[] OPS2 = {"==", "!=", "<=", ">=", "**", "//", "+=", "-=", "*=", "/=", "%=", "->", "<<", ">>", "&=", "|=", "^=", ":="};
    private static final String OPS1 = "+-*/%<>=()[]{},:.;@&|^~";

    private final String src;
    private final int lineOffset;
    private int pos = 0;
    private int line;
    private final List<Token> out = new ArrayList<>();

    public Lexer(String src) {
        this(src, 1);
    }

    public Lexer(String src, int firstLine) {
        this.src = src.replace("\r\n", "\n").replace('\r', '\n');
        this.lineOffset = firstLine;
        this.line = firstLine;
    }

    public List<Token> tokenize() {
        Deque<Integer> indents = new ArrayDeque<>();
        indents.push(0);
        int depth = 0;
        boolean lineStart = true;
        int n = src.length();
        while (pos < n) {
            if (lineStart && depth == 0) {
                int col = 0;
                int p = pos;
                while (p < n && (src.charAt(p) == ' ' || src.charAt(p) == '\t')) {
                    col += src.charAt(p) == '\t' ? 4 - (col % 4) : 1;
                    p++;
                }
                if (p >= n) { pos = p; break; }
                char c = src.charAt(p);
                if (c == '\n' || c == '#') {
                    while (p < n && src.charAt(p) != '\n') p++;
                    pos = p + 1;
                    line++;
                    continue;
                }
                pos = p;
                if (col > indents.peek()) {
                    indents.push(col);
                    add(T.INDENT, "", null);
                } else {
                    while (col < indents.peek()) {
                        indents.pop();
                        add(T.DEDENT, "", null);
                    }
                    if (col != indents.peek())
                        throw new PyError("IndentationError", "отступ не совпадает ни с одним внешним блоком", line);
                }
                lineStart = false;
            }
            char c = src.charAt(pos);
            if (c == '\n') {
                if (depth == 0) {
                    if (!out.isEmpty() && out.get(out.size() - 1).type != T.NEWLINE) add(T.NEWLINE, "", null);
                    lineStart = true;
                }
                pos++;
                line++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f') { pos++; continue; }
            if (c == '#') {
                while (pos < n && src.charAt(pos) != '\n') pos++;
                continue;
            }
            if (c == '\\' && pos + 1 < n && src.charAt(pos + 1) == '\n') {
                pos += 2;
                line++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < n && Character.isDigit(src.charAt(pos + 1)))) {
                number();
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int s = pos;
                while (pos < n && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                String word = src.substring(s, pos);
                String lw = word.toLowerCase();
                if (pos < n && (src.charAt(pos) == '"' || src.charAt(pos) == '\'')
                        && (lw.equals("f") || lw.equals("r") || lw.equals("rf") || lw.equals("fr") || lw.equals("b") || lw.equals("u"))) {
                    string(lw.contains("f"), lw.contains("r"));
                } else {
                    add(T.NAME, word, null);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                string(false, false);
                continue;
            }
            String op = null;
            for (String o : OPS3) if (src.startsWith(o, pos)) { op = o; break; }
            if (op == null) for (String o : OPS2) if (src.startsWith(o, pos)) { op = o; break; }
            if (op == null && OPS1.indexOf(c) >= 0) op = String.valueOf(c);
            if (op == null) {
                if (c == '!' ) throw new PyError("SyntaxError", "неизвестный символ '!' (может, имели в виду != или not?)", line);
                throw new PyError("SyntaxError", "неизвестный символ '" + c + "'", line);
            }
            if (op.equals("(") || op.equals("[") || op.equals("{")) depth++;
            if (op.equals(")") || op.equals("]") || op.equals("}")) depth = Math.max(0, depth - 1);
            pos += op.length();
            add(T.OP, op, null);
        }
        if (!out.isEmpty() && out.get(out.size() - 1).type != T.NEWLINE) add(T.NEWLINE, "", null);
        while (indents.size() > 1) {
            indents.pop();
            add(T.DEDENT, "", null);
        }
        add(T.EOF, "", null);
        return out;
    }

    private void add(T type, String text, Object value) {
        out.add(new Token(type, text, value, line));
    }

    private void number() {
        int s = pos;
        int n = src.length();
        if (src.charAt(pos) == '0' && pos + 1 < n && "xXbBoO".indexOf(src.charAt(pos + 1)) >= 0) {
            char kind = Character.toLowerCase(src.charAt(pos + 1));
            pos += 2;
            int ds = pos;
            while (pos < n && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            String digits = src.substring(ds, pos).replace("_", "");
            int radix = kind == 'x' ? 16 : kind == 'b' ? 2 : 8;
            try {
                add(T.NUMBER, src.substring(s, pos), Long.parseLong(digits, radix));
            } catch (NumberFormatException e) {
                throw new PyError("SyntaxError", "неверное число '" + src.substring(s, pos) + "'", line);
            }
            return;
        }
        boolean isFloat = false;
        while (pos < n && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        if (pos < n && src.charAt(pos) == '.' && !(pos + 1 < n && src.charAt(pos + 1) == '.')) {
            isFloat = true;
            pos++;
            while (pos < n && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        }
        if (pos < n && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int save = pos;
            pos++;
            if (pos < n && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos < n && Character.isDigit(src.charAt(pos))) {
                isFloat = true;
                while (pos < n && Character.isDigit(src.charAt(pos))) pos++;
            } else {
                pos = save;
            }
        }
        String text = src.substring(s, pos).replace("_", "");
        if (pos < n && (Character.isLetter(src.charAt(pos)) || src.charAt(pos) == '_'))
            throw new PyError("SyntaxError", "имя не может начинаться с цифры: '" + text + src.charAt(pos) + "...'", line);
        try {
            if (isFloat) add(T.NUMBER, text, Double.parseDouble(text));
            else {
                try {
                    add(T.NUMBER, text, Long.parseLong(text));
                } catch (NumberFormatException e) {
                    add(T.NUMBER, text, Double.parseDouble(text));
                }
            }
        } catch (NumberFormatException e) {
            throw new PyError("SyntaxError", "неверное число '" + text + "'", line);
        }
    }

    private void string(boolean f, boolean raw) {
        int n = src.length();
        char q = src.charAt(pos);
        boolean triple = src.startsWith("" + q + q + q, pos);
        int startLine = line;
        pos += triple ? 3 : 1;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= n) throw new PyError("SyntaxError", "строка не закрыта кавычкой " + q, startLine);
            char c = src.charAt(pos);
            if (triple) {
                if (src.startsWith("" + q + q + q, pos)) { pos += 3; break; }
            } else if (c == q) {
                pos++;
                break;
            } else if (c == '\n') {
                throw new PyError("SyntaxError", "строка не закрыта кавычкой " + q + " до конца строки", startLine);
            }
            if (c == '\n') line++;
            if (c == '\\' && pos + 1 < n) {
                char e = src.charAt(pos + 1);
                if (raw || f) {
                    // f-strings keep escapes for later processing of the literal parts
                    if (raw) { sb.append(c).append(e); pos += 2; if (e == '\n') line++; continue; }
                }
                pos += 2;
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case '\n' -> line++;
                    case 'u' -> {
                        if (pos + 4 <= n) {
                            try {
                                sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                                pos += 4;
                            } catch (NumberFormatException ex) {
                                sb.append("\\u");
                            }
                        }
                    }
                    case 'x' -> {
                        if (pos + 2 <= n) {
                            try {
                                sb.append((char) Integer.parseInt(src.substring(pos, pos + 2), 16));
                                pos += 2;
                            } catch (NumberFormatException ex) {
                                sb.append("\\x");
                            }
                        }
                    }
                    default -> sb.append('\\').append(e);
                }
                continue;
            }
            sb.append(c);
            pos++;
        }
        out.add(new Token(f ? T.FSTRING : T.STRING, sb.toString(), sb.toString(), startLine));
    }
}
