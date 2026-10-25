package com.miramwh.codingworld.py;

/** A Python-level error with the source line it happened on. */
public class PyError extends RuntimeException {
    public final String type;
    public final String msg;
    public int line;
    /** The Python value raised by the script (a PyException), if any. */
    public Object value;
    /** Function calls the error passed through, innermost first. */
    public final java.util.List<String> trace = new java.util.ArrayList<>();

    public PyError(String type, String msg) {
        this(type, msg, 0);
    }

    public PyError(String type, String msg, int line) {
        super(type + ": " + msg, null, false, false);
        this.type = type;
        this.msg = msg;
        this.line = line;
    }

    public PyError at(int line) {
        if (this.line <= 0) this.line = line;
        return this;
    }

    public String pretty() {
        StringBuilder sb = new StringBuilder();
        if (line > 0) sb.append("Строка ").append(line);
        for (int i = 0; i < Math.min(trace.size(), 4); i++) sb.append(i == 0 ? " в " : ", ").append(trace.get(i));
        if (sb.length() > 0) sb.append(": ");
        return sb.append(type).append(": ").append(msg).toString();
    }
}
