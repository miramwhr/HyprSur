package com.miramwh.codingworld.server;

import com.miramwh.codingworld.api.ApiDocs;
import com.miramwh.codingworld.py.Py;
import com.miramwh.codingworld.py.PyError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Binds positional and keyword arguments of a world function using its documented signature. */
final class Args {
    private static final Object MISSING = new Object();

    final String fn;
    private final String[] names;
    private final Object[] vals;

    private Args(String fn, String[] names, Object[] vals) {
        this.fn = fn;
        this.names = names;
        this.vals = vals;
    }

    static List<String[]> parseParams(String params) {
        List<String[]> out = new ArrayList<>();
        if (params.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (char c : params.toCharArray()) {
            if (c == '"') inStr = !inStr;
            if (c == ',' && !inStr) {
                out.add(split(cur.toString().trim()));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(split(cur.toString().trim()));
        return out;
    }

    private static String[] split(String p) {
        int eq = p.indexOf('=');
        if (eq < 0) return new String[]{p, null};
        return new String[]{p.substring(0, eq).trim(), p.substring(eq + 1).trim()};
    }

    static Object literal(String s) {
        if (s == null) return MISSING;
        if (s.equals("None")) return null;
        if (s.equals("True")) return true;
        if (s.equals("False")) return false;
        if (s.startsWith("\"")) return s.substring(1, s.length() - 1);
        if (s.contains(".")) return Double.parseDouble(s);
        return Long.parseLong(s);
    }

    static Args bind(ApiDocs.Fn doc, List<String[]> params, List<Object> a, Map<String, Object> kw) {
        int n = params.size();
        String[] names = new String[n];
        Object[] vals = new Object[n];
        for (int i = 0; i < n; i++) {
            names[i] = params.get(i)[0];
            vals[i] = MISSING;
        }
        if (a.size() > n)
            throw new PyError("TypeError", doc.name() + "() принимает максимум " + n + " аргумент(ов), а передано " + a.size() + ". Формат: " + doc.signature());
        for (int i = 0; i < a.size(); i++) vals[i] = a.get(i);
        for (var e : kw.entrySet()) {
            int idx = -1;
            for (int i = 0; i < n; i++) if (names[i].equals(e.getKey())) idx = i;
            if (idx < 0) throw new PyError("TypeError", doc.name() + "() не знает аргумента '" + e.getKey() + "'. Формат: " + doc.signature());
            vals[idx] = e.getValue();
        }
        for (int i = 0; i < n; i++) {
            if (vals[i] == MISSING) {
                Object d = literal(params.get(i)[1]);
                if (d == MISSING)
                    throw new PyError("TypeError", doc.name() + "(): не хватает аргумента '" + names[i] + "'. Формат: " + doc.signature());
                vals[i] = d;
            }
        }
        return new Args(doc.name(), names, vals);
    }

    Object o(int i) {
        return vals[i];
    }

    boolean isNone(int i) {
        return vals[i] == null;
    }

    double d(int i) {
        Object v = vals[i];
        if (!Py.isNum(v)) throw new PyError("TypeError", fn + "(): '" + names[i] + "' должно быть числом, а получено " + Py.repr(v));
        double d = Py.toDouble(v);
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new PyError("ValueError", fn + "(): '" + names[i] + "' — неправильное число");
        return d;
    }

    int i(int i) {
        double d = d(i);
        if (d > 3.0e7 || d < -3.0e7) throw new PyError("ValueError", fn + "(): '" + names[i] + "' слишком большое число");
        return (int) Math.floor(d);
    }

    /** A coordinate: whole numbers are block centres, fractions are exact. */
    double coord(int i) {
        Object v = vals[i];
        double d = d(i);
        if (d > 3.0e7 || d < -3.0e7) throw new PyError("ValueError", fn + "(): координата слишком далеко");
        return Py.isInt(v) ? d + 0.5 : d;
    }

    String s(int i) {
        Object v = vals[i];
        if (!(v instanceof String s)) throw new PyError("TypeError", fn + "(): '" + names[i] + "' должно быть строкой в кавычках, а получено " + Py.repr(v));
        return s;
    }

    String text(int i) {
        return Py.str(vals[i]);
    }

    boolean b(int i) {
        return Py.truthy(vals[i]);
    }
}
