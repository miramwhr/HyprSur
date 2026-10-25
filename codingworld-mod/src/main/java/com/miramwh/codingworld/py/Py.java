package com.miramwh.codingworld.py;

import com.miramwh.codingworld.py.Values.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Core Python semantics: conversions, printing, arithmetic, comparison and iteration. */
public final class Py {
    private Py() {}

    public static final int MAX_SIZE = 2_000_000;
    static final java.util.Set<String> TYPE_NAMES = java.util.Set.of("int", "float", "str", "bool", "list", "tuple", "dict", "set", "range");

    public static String typeName(Object o) {
        if (o == null) return "NoneType";
        if (o instanceof Boolean) return "bool";
        if (o instanceof Long) return "int";
        if (o instanceof Double) return "float";
        if (o instanceof String) return "str";
        if (o instanceof PyList) return "list";
        if (o instanceof PyTuple) return "tuple";
        if (o instanceof PyDict) return "dict";
        if (o instanceof PySet) return "set";
        if (o instanceof PyRange) return "range";
        if (o instanceof PyFunction || o instanceof Builtin || o instanceof BoundMethod) return "function";
        if (o instanceof PyModule) return "module";
        if (o instanceof ExcClass) return "type";
        if (o instanceof PyException e) return e.cls.name;
        return o.getClass().getSimpleName();
    }

    public static boolean isNum(Object o) {
        return o instanceof Long || o instanceof Double || o instanceof Boolean;
    }

    public static boolean isInt(Object o) {
        return o instanceof Long || o instanceof Boolean;
    }

    public static long toLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Double d) {
            if (d != Math.floor(d)) throw new PyError("TypeError", "ожидалось целое число, а получено " + repr(o));
            return d.longValue();
        }
        throw new PyError("TypeError", "ожидалось целое число, а получено " + typeName(o) + " " + repr(o));
    }

    public static double toDouble(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Double d) return d;
        if (o instanceof Boolean b) return b ? 1 : 0;
        throw new PyError("TypeError", "ожидалось число, а получено " + typeName(o) + " " + repr(o));
    }

    public static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Long l) return l != 0;
        if (o instanceof Double d) return d != 0;
        if (o instanceof String s) return !s.isEmpty();
        if (o instanceof PyList l) return !l.isEmpty();
        if (o instanceof PyTuple t) return t.size() > 0;
        if (o instanceof PyDict d) return d.size() > 0;
        if (o instanceof PySet s) return !s.set.isEmpty();
        if (o instanceof PyRange r) return r.size() > 0;
        return true;
    }

    // ---------- printing ----------
    public static String str(Object o) {
        if (o instanceof String s) return s;
        if (o instanceof PyException e) return e.message;
        return repr(o);
    }

    public static String floatRepr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        double a = Math.abs(d);
        if (a >= 1e16 || a < 1e-4) {
            String s = Double.toString(d); // like 1.0E16
            int e = s.indexOf('E');
            String mant = s.substring(0, e);
            int exp = Integer.parseInt(s.substring(e + 1));
            if (mant.endsWith(".0")) mant = mant.substring(0, mant.length() - 2);
            return mant + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        }
        String s = new BigDecimal(Double.toString(d)).toPlainString();
        if (!s.contains(".")) s += ".0";
        return s;
    }

    public static String repr(Object o) {
        return repr(o, 0);
    }

    private static String repr(Object o, int depth) {
        if (depth > 20) return "...";
        if (o == null) return "None";
        if (o instanceof Boolean b) return b ? "True" : "False";
        if (o instanceof Long l) return l.toString();
        if (o instanceof Double d) return floatRepr(d);
        if (o instanceof String s) {
            String q = s.contains("'") && !s.contains("\"") ? "\"" : "'";
            StringBuilder sb = new StringBuilder(q);
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '\\' -> sb.append("\\\\");
                    default -> {
                        if (String.valueOf(c).equals(q)) sb.append('\\');
                        sb.append(c);
                    }
                }
            }
            return sb.append(q).toString();
        }
        if (o instanceof PyList l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(l.get(i) == l ? "[...]" : repr(l.get(i), depth + 1));
                if (sb.length() > 100_000) return sb.append(", ...]").toString();
            }
            return sb.append(']').toString();
        }
        if (o instanceof PyTuple t) {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < t.items.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(repr(t.items[i], depth + 1));
            }
            if (t.items.length == 1) sb.append(',');
            return sb.append(')').toString();
        }
        if (o instanceof PyDict d) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : d.map.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(d.keys.get(e.getKey()), depth + 1)).append(": ").append(e.getValue() == d ? "{...}" : repr(e.getValue(), depth + 1));
                if (sb.length() > 100_000) return sb.append(", ...}").toString();
            }
            return sb.append('}').toString();
        }
        if (o instanceof PySet s) {
            if (s.set.isEmpty()) return "set()";
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Object x : s.set) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(x, depth + 1));
            }
            return sb.append('}').toString();
        }
        if (o instanceof PyRange r) {
            return "range(" + r.start + ", " + r.stop + (r.step != 1 ? ", " + r.step : "") + ")";
        }
        if (o instanceof PyFunction f) return "<функция " + f.name + ">";
        if (o instanceof Builtin b) return TYPE_NAMES.contains(b.name) ? "<class '" + b.name + "'>" : "<встроенная функция " + b.name + ">";
        if (o instanceof BoundMethod m) return "<метод " + m.name + ">";
        if (o instanceof PyModule m) return "<модуль " + m.name + ">";
        if (o instanceof ExcClass c) return "<class '" + c.name + "'>";
        if (o instanceof PyException e) return e.cls.name + "(" + (e.message.isEmpty() ? "" : repr(e.message)) + ")";
        return String.valueOf(o);
    }

    // ---------- equality / ordering ----------
    public static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (isNum(a) && isNum(b)) {
            if (isInt(a) && isInt(b)) return toLong(a) == toLong(b);
            return toDouble(a) == toDouble(b);
        }
        if (a instanceof String s) return s.equals(b);
        if (a instanceof PyList l1 && b instanceof PyList l2) {
            if (l1.size() != l2.size()) return false;
            for (int i = 0; i < l1.size(); i++) if (!eq(l1.get(i), l2.get(i))) return false;
            return true;
        }
        if (a instanceof PyTuple t1 && b instanceof PyTuple t2) return t1.equals(t2);
        if (a instanceof PyDict d1 && b instanceof PyDict d2) {
            if (d1.size() != d2.size()) return false;
            for (var e : d1.map.entrySet()) {
                if (!d2.map.containsKey(e.getKey()) || !eq(e.getValue(), d2.map.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof PySet s1 && b instanceof PySet s2) return s1.set.equals(s2.set);
        if (a instanceof PyRange r1 && b instanceof PyRange r2) return r1.start == r2.start && r1.stop == r2.stop && r1.step == r2.step;
        return a.equals(b);
    }

    public static int hash(Object o) {
        Object k = Values.key(o);
        return k == null ? 0 : k.hashCode();
    }

    public static int compare(Object a, Object b, String op) {
        if (isNum(a) && isNum(b)) {
            if (isInt(a) && isInt(b)) return Long.compare(toLong(a), toLong(b));
            return Double.compare(toDouble(a), toDouble(b));
        }
        if (a instanceof String s1 && b instanceof String s2) return Integer.signum(s1.compareTo(s2));
        if ((a instanceof PyList || a instanceof PyTuple) && a.getClass() == b.getClass()) {
            List<Object> l1 = toList(a), l2 = toList(b);
            for (int i = 0; i < Math.min(l1.size(), l2.size()); i++) {
                if (!eq(l1.get(i), l2.get(i))) return compare(l1.get(i), l2.get(i), op);
            }
            return Integer.compare(l1.size(), l2.size());
        }
        throw new PyError("TypeError", "нельзя сравнить " + typeName(a) + " и " + typeName(b) + " оператором " + op
                + (a instanceof String || b instanceof String ? " (переведите строку в число через int())" : ""));
    }

    // ---------- arithmetic ----------
    private static PyError opError(String op, Object a, Object b) {
        String hint = "";
        if (op.equals("+") && (a instanceof String || b instanceof String))
            hint = " (чтобы склеить текст с числом, используйте str(число))";
        return new PyError("TypeError", "нельзя применить '" + op + "' к " + typeName(a) + " и " + typeName(b) + hint);
    }

    public static Object binop(String op, Object a, Object b) {
        switch (op) {
            case "+":
                if (isInt(a) && isInt(b)) {
                    long x = toLong(a), y = toLong(b);
                    long r = x + y;
                    if (((x ^ r) & (y ^ r)) < 0) return (double) x + y;
                    return r;
                }
                if (isNum(a) && isNum(b)) return toDouble(a) + toDouble(b);
                if (a instanceof String s1 && b instanceof String s2) {
                    if ((long) s1.length() + s2.length() > MAX_SIZE * 10L) throw new PyError("MemoryError", "строка слишком длинная");
                    return s1 + s2;
                }
                if (a instanceof PyList l1 && b instanceof PyList l2) {
                    PyList r = new PyList(l1);
                    r.addAll(l2);
                    checkSize(r.size());
                    return r;
                }
                if (a instanceof PyTuple t1 && b instanceof PyTuple t2) {
                    Object[] r = Arrays.copyOf(t1.items, t1.items.length + t2.items.length);
                    System.arraycopy(t2.items, 0, r, t1.items.length, t2.items.length);
                    return new PyTuple(r);
                }
                throw opError(op, a, b);
            case "-":
                if (isInt(a) && isInt(b)) {
                    long x = toLong(a), y = toLong(b);
                    long r = x - y;
                    if (((x ^ y) & (x ^ r)) < 0) return (double) x - y;
                    return r;
                }
                if (isNum(a) && isNum(b)) return toDouble(a) - toDouble(b);
                if (a instanceof PySet s1 && b instanceof PySet s2) {
                    PySet r = new PySet();
                    for (Object o : s1.set) if (!s2.set.contains(o)) r.set.add(o);
                    return r;
                }
                throw opError(op, a, b);
            case "*":
                if (isInt(a) && isInt(b)) {
                    long x = toLong(a), y = toLong(b);
                    long hi = Math.multiplyHigh(x, y), lo = x * y;
                    if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) return lo;
                    return (double) x * y;
                }
                if (isNum(a) && isNum(b)) return toDouble(a) * toDouble(b);
                if (a instanceof String s && isInt(b)) return repeat(s, toLong(b));
                if (b instanceof String s && isInt(a)) return repeat(s, toLong(a));
                if (a instanceof PyList l && isInt(b)) return repeatList(l, toLong(b));
                if (b instanceof PyList l && isInt(a)) return repeatList(l, toLong(a));
                if (a instanceof PyTuple t && isInt(b)) return new PyTuple(repeatList(new PyList(Arrays.asList(t.items)), toLong(b)));
                throw opError(op, a, b);
            case "/":
                if (isNum(a) && isNum(b)) {
                    double y = toDouble(b);
                    if (y == 0) throw new PyError("ZeroDivisionError", "деление на ноль");
                    return toDouble(a) / y;
                }
                throw opError(op, a, b);
            case "//":
                if (isInt(a) && isInt(b)) {
                    long y = toLong(b);
                    if (y == 0) throw new PyError("ZeroDivisionError", "целочисленное деление на ноль");
                    return Math.floorDiv(toLong(a), y);
                }
                if (isNum(a) && isNum(b)) {
                    double y = toDouble(b);
                    if (y == 0) throw new PyError("ZeroDivisionError", "деление на ноль");
                    return Math.floor(toDouble(a) / y);
                }
                throw opError(op, a, b);
            case "%":
                if (a instanceof String s) return Methods.percentFormat(s, b);
                if (isInt(a) && isInt(b)) {
                    long y = toLong(b);
                    if (y == 0) throw new PyError("ZeroDivisionError", "остаток от деления на ноль");
                    return Math.floorMod(toLong(a), y);
                }
                if (isNum(a) && isNum(b)) {
                    double x = toDouble(a), y = toDouble(b);
                    if (y == 0) throw new PyError("ZeroDivisionError", "остаток от деления на ноль");
                    double r = x - Math.floor(x / y) * y;
                    return r;
                }
                throw opError(op, a, b);
            case "**":
                if (isInt(a) && isInt(b) && toLong(b) >= 0) {
                    long base = toLong(a), e = toLong(b), r = 1;
                    double approx = Math.pow(base, e);
                    if (Math.abs(approx) > 9e18) return approx;
                    for (long i = 0; i < e; i++) r *= base;
                    return r;
                }
                if (isNum(a) && isNum(b)) {
                    double x = toDouble(a), y = toDouble(b);
                    if (x == 0 && y < 0) throw new PyError("ZeroDivisionError", "0 нельзя возводить в отрицательную степень");
                    return Math.pow(x, y);
                }
                throw opError(op, a, b);
            case "&":
                if (isInt(a) && isInt(b)) {
                    if (a instanceof Boolean && b instanceof Boolean) return (Boolean) a & (Boolean) b;
                    return toLong(a) & toLong(b);
                }
                if (a instanceof PySet s1 && b instanceof PySet s2) {
                    PySet r = new PySet();
                    for (Object o : s1.set) if (s2.set.contains(o)) r.set.add(o);
                    return r;
                }
                throw opError(op, a, b);
            case "|":
                if (isInt(a) && isInt(b)) {
                    if (a instanceof Boolean && b instanceof Boolean) return (Boolean) a | (Boolean) b;
                    return toLong(a) | toLong(b);
                }
                if (a instanceof PySet s1 && b instanceof PySet s2) {
                    PySet r = new PySet();
                    r.set.addAll(s1.set);
                    r.set.addAll(s2.set);
                    return r;
                }
                if (a instanceof PyDict d1 && b instanceof PyDict d2) {
                    PyDict r = new PyDict();
                    for (Object k : d1.keyList()) r.put(k, d1.get(k));
                    for (Object k : d2.keyList()) r.put(k, d2.get(k));
                    return r;
                }
                throw opError(op, a, b);
            case "^":
                if (isInt(a) && isInt(b)) return toLong(a) ^ toLong(b);
                throw opError(op, a, b);
            case "<<":
                if (isInt(a) && isInt(b)) return toLong(a) << toLong(b);
                throw opError(op, a, b);
            case ">>":
                if (isInt(a) && isInt(b)) return toLong(a) >> toLong(b);
                throw opError(op, a, b);
            default:
                throw new PyError("SyntaxError", "неизвестный оператор " + op);
        }
    }

    private static void checkSize(long n) {
        if (n > MAX_SIZE) throw new PyError("MemoryError", "слишком большой список (больше " + MAX_SIZE + " элементов)");
    }

    private static String repeat(String s, long n) {
        if (n <= 0) return "";
        if (s.length() * n > MAX_SIZE * 10L) throw new PyError("MemoryError", "строка слишком длинная");
        return s.repeat((int) n);
    }

    private static PyList repeatList(PyList l, long n) {
        PyList r = new PyList();
        if (n <= 0) return r;
        checkSize(l.size() * n);
        for (long i = 0; i < n; i++) r.addAll(l);
        return r;
    }

    // ---------- containers ----------
    public static Iterator<Object> iter(Object o) {
        if (o instanceof PyList l) return new ArrayList<>(l).iterator();
        if (o instanceof PyTuple t) return Arrays.asList(t.items).iterator();
        if (o instanceof PyRange r) return r.iterator();
        if (o instanceof String s) {
            List<Object> chars = new ArrayList<>();
            s.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
            return chars.iterator();
        }
        if (o instanceof PyDict d) return d.keyList().iterator();
        if (o instanceof PySet s) return new ArrayList<>(s.set).iterator();
        throw new PyError("TypeError", "по " + typeName(o) + " нельзя пройти циклом for" + (isNum(o) ? " (может, нужно range(" + repr(o) + ")?)" : ""));
    }

    public static List<Object> toList(Object o) {
        if (o instanceof PyList l) return l;
        if (o instanceof PyTuple t) return Arrays.asList(t.items);
        List<Object> r = new ArrayList<>();
        if (o instanceof PyRange rg) checkSize(rg.size());
        for (Iterator<Object> it = iter(o); it.hasNext(); ) {
            r.add(it.next());
            if (r.size() > MAX_SIZE) checkSize(r.size());
        }
        return r;
    }

    public static long len(Object o) {
        if (o instanceof String s) return s.codePointCount(0, s.length());
        if (o instanceof PyList l) return l.size();
        if (o instanceof PyTuple t) return t.size();
        if (o instanceof PyDict d) return d.size();
        if (o instanceof PySet s) return s.set.size();
        if (o instanceof PyRange r) return r.size();
        throw new PyError("TypeError", "у " + typeName(o) + " нет длины len()");
    }

    public static boolean contains(Object container, Object item) {
        if (container instanceof String s) {
            if (!(item instanceof String sub)) throw new PyError("TypeError", "'in <str>' требует строку слева, а не " + typeName(item));
            return s.contains(sub);
        }
        if (container instanceof PyDict d) return d.has(item);
        if (container instanceof PySet s) return s.has(item);
        if (container instanceof PyRange r) {
            if (!isNum(item)) return false;
            double v = toDouble(item);
            if (v != Math.floor(v)) return false;
            long x = (long) v;
            if (r.step > 0 ? (x < r.start || x >= r.stop) : (x > r.start || x <= r.stop)) return false;
            return (x - r.start) % r.step == 0;
        }
        if (container instanceof PyList || container instanceof PyTuple) {
            for (Object o : toList(container)) if (eq(o, item)) return true;
            return false;
        }
        throw new PyError("TypeError", "оператор 'in' не работает с " + typeName(container));
    }

    private static int index(long i, int size, String what) {
        long j = i < 0 ? i + size : i;
        if (j < 0 || j >= size) throw new PyError("IndexError", "индекс " + i + " вне " + what + " (длина " + size + ")");
        return (int) j;
    }

    public static Object getItem(Object o, Object idx) {
        if (idx instanceof Slice s) return slice(o, s);
        if (o instanceof PyList l) return l.get(index(toLong(idx), l.size(), "списка"));
        if (o instanceof PyTuple t) return t.items[index(toLong(idx), t.size(), "кортежа")];
        if (o instanceof String s) {
            int[] cps = s.codePoints().toArray();
            return new String(Character.toChars(cps[index(toLong(idx), cps.length, "строки")]));
        }
        if (o instanceof PyRange r) {
            long n = r.size();
            long i = toLong(idx);
            if (i < 0) i += n;
            if (i < 0 || i >= n) throw new PyError("IndexError", "индекс вне range");
            return r.get(i);
        }
        if (o instanceof PyDict d) {
            if (!d.has(idx)) throw new PyError("KeyError", "нет ключа " + repr(idx) + " в словаре");
            return d.get(idx);
        }
        throw new PyError("TypeError", typeName(o) + " не поддерживает индексацию []");
    }

    public static void setItem(Object o, Object idx, Object v) {
        if (o instanceof PyList l) {
            if (idx instanceof Slice s) {
                long[] r = s.indices(l.size());
                if (r[2] != 1) throw new PyError("ValueError", "присваивание срезу с шагом не поддерживается");
                int lo = (int) r[0], hi = (int) Math.max(r[0], r[1]);
                List<Object> items = new ArrayList<>(toList(v));
                l.subList(lo, hi).clear();
                l.addAll(lo, items);
                return;
            }
            l.set(index(toLong(idx), l.size(), "списка"), v);
            return;
        }
        if (o instanceof PyDict d) {
            d.put(idx, v);
            return;
        }
        if (o instanceof String) throw new PyError("TypeError", "строки нельзя изменять по индексу");
        if (o instanceof PyTuple) throw new PyError("TypeError", "кортежи нельзя изменять");
        throw new PyError("TypeError", typeName(o) + " не поддерживает присваивание по индексу");
    }

    public static void delItem(Object o, Object idx) {
        if (o instanceof PyList l) {
            if (idx instanceof Slice s) {
                long[] r = s.indices(l.size());
                List<Object> keep = new ArrayList<>();
                java.util.Set<Long> drop = new java.util.HashSet<>();
                for (long i = r[0]; r[2] > 0 ? i < r[1] : i > r[1]; i += r[2]) drop.add(i);
                for (int i = 0; i < l.size(); i++) if (!drop.contains((long) i)) keep.add(l.get(i));
                l.clear();
                l.addAll(keep);
                return;
            }
            l.remove(index(toLong(idx), l.size(), "списка"));
            return;
        }
        if (o instanceof PyDict d) {
            if (!d.has(idx)) throw new PyError("KeyError", "нет ключа " + repr(idx));
            d.remove(idx);
            return;
        }
        throw new PyError("TypeError", typeName(o) + " не поддерживает del");
    }

    /** Runtime slice value. */
    public static final class Slice {
        final Object lo, hi, step;
        Slice(Object lo, Object hi, Object step) { this.lo = lo; this.hi = hi; this.step = step; }

        long[] indices(long n) {
            long st = step == null ? 1 : toLong(step);
            if (st == 0) throw new PyError("ValueError", "шаг среза не может быть 0");
            long a, b;
            if (st > 0) {
                a = lo == null ? 0 : clamp(toLong(lo), n, 0, n);
                b = hi == null ? n : clamp(toLong(hi), n, 0, n);
            } else {
                a = lo == null ? n - 1 : clamp(toLong(lo), n, -1, n - 1);
                b = hi == null ? -1 : clamp(toLong(hi), n, -1, n - 1);
            }
            return new long[]{a, b, st};
        }

        private static long clamp(long i, long n, long min, long max) {
            if (i < 0) i += n;
            return Math.max(min, Math.min(max, i));
        }
    }

    private static Object slice(Object o, Slice s) {
        List<Object> src;
        if (o instanceof String str) {
            src = new ArrayList<>();
            str.codePoints().forEach(cp -> src.add(new String(Character.toChars(cp))));
        } else if (o instanceof PyList || o instanceof PyTuple || o instanceof PyRange) {
            src = toList(o);
        } else {
            throw new PyError("TypeError", typeName(o) + " не поддерживает срезы [a:b]");
        }
        long[] r = s.indices(src.size());
        List<Object> out = new ArrayList<>();
        for (long i = r[0]; r[2] > 0 ? i < r[1] : i > r[1]; i += r[2]) out.add(src.get((int) i));
        if (o instanceof String) {
            StringBuilder sb = new StringBuilder();
            for (Object c : out) sb.append(c);
            return sb.toString();
        }
        if (o instanceof PyTuple) return new PyTuple(out);
        return new PyList(out);
    }

    // ---------- formatting ----------
    /** Implements the format-spec mini language: [[fill]align][sign][0][width][,][.precision][type]. */
    public static String format(Object v, String spec) {
        if (spec == null || spec.isEmpty()) return str(v);
        int i = 0;
        char fill = ' ';
        char align = 0;
        if (spec.length() >= 2 && "<>^=".indexOf(spec.charAt(1)) >= 0) {
            fill = spec.charAt(0);
            align = spec.charAt(1);
            i = 2;
        } else if ("<>^=".indexOf(spec.charAt(0)) >= 0) {
            align = spec.charAt(0);
            i = 1;
        }
        char sign = '-';
        if (i < spec.length() && "+- ".indexOf(spec.charAt(i)) >= 0) sign = spec.charAt(i++);
        boolean zero = false;
        if (i < spec.length() && spec.charAt(i) == '0') {
            zero = true;
            i++;
        }
        int width = 0;
        while (i < spec.length() && Character.isDigit(spec.charAt(i))) width = width * 10 + (spec.charAt(i++) - '0');
        boolean comma = false;
        if (i < spec.length() && (spec.charAt(i) == ',' || spec.charAt(i) == '_')) {
            comma = true;
            i++;
        }
        int prec = -1;
        if (i < spec.length() && spec.charAt(i) == '.') {
            i++;
            prec = 0;
            while (i < spec.length() && Character.isDigit(spec.charAt(i))) prec = prec * 10 + (spec.charAt(i++) - '0');
        }
        char type = i < spec.length() ? spec.charAt(i) : 0;
        String body;
        boolean numeric = isNum(v) && !(v instanceof Boolean && type == 0);
        switch (type) {
            case 'f', 'F', '%' -> {
                double d = toDouble(v) * (type == '%' ? 100 : 1);
                body = String.format(Locale.ROOT, "%" + (comma ? "," : "") + "." + (prec < 0 ? 6 : prec) + "f", d) + (type == '%' ? "%" : "");
            }
            case 'e', 'E' -> body = String.format(Locale.ROOT, "%." + (prec < 0 ? 6 : prec) + (type == 'e' ? "e" : "E"), toDouble(v));
            case 'd' -> body = comma ? String.format(Locale.ROOT, "%,d", toLong(v)) : Long.toString(toLong(v));
            case 'x' -> body = Long.toHexString(toLong(v));
            case 'X' -> body = Long.toHexString(toLong(v)).toUpperCase();
            case 'b' -> body = Long.toBinaryString(toLong(v));
            case 'o' -> body = Long.toOctalString(toLong(v));
            case 'c' -> body = new String(Character.toChars((int) toLong(v)));
            case 's', 0 -> {
                if (numeric && prec >= 0) body = String.format(Locale.ROOT, "%." + prec + "f", toDouble(v));
                else if (numeric && comma && isInt(v)) body = String.format(Locale.ROOT, "%,d", toLong(v));
                else {
                    body = str(v);
                    if (prec >= 0 && body.length() > prec) body = body.substring(0, prec);
                }
            }
            case 'g', 'G' -> body = floatRepr(toDouble(v));
            default -> throw new PyError("ValueError", "неизвестный формат '" + type + "'");
        }
        if (numeric && sign != '-' && !body.startsWith("-")) body = sign + body;
        if (align == 0) align = numeric ? '>' : '<';
        if (zero && align == '>' && numeric) {
            fill = '0';
            align = '=';
        }
        int pad = width - body.length();
        if (pad <= 0) return body;
        String f = String.valueOf(fill).repeat(pad);
        return switch (align) {
            case '<' -> body + f;
            case '^' -> String.valueOf(fill).repeat(pad / 2) + body + String.valueOf(fill).repeat(pad - pad / 2);
            case '=' -> (body.startsWith("-") || body.startsWith("+")) ? body.charAt(0) + f + body.substring(1) : f + body;
            default -> f + body;
        };
    }
}
