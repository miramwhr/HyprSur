package com.miramwh.codingworld.py;

import com.miramwh.codingworld.py.Values.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Standard Python built-in functions and the math / random / time modules. */
final class Builtins {
    private Builtins() {}

    static void argc(String name, List<Object> a, int min, int max) {
        if (a.size() < min || a.size() > max) {
            String exp = min == max ? String.valueOf(min) : max == Integer.MAX_VALUE ? "минимум " + min : min + "–" + max;
            throw new PyError("TypeError", name + "() ожидает " + exp + " аргумент(ов), а получено " + a.size());
        }
    }

    static void install(Interpreter in) {
        in.define("print", (it, a, kw) -> {
            String sep = kw.containsKey("sep") ? Py.str(kw.get("sep")) : " ";
            String end = kw.containsKey("end") ? Py.str(kw.get("end")) : "\n";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(sep);
                sb.append(Py.str(a.get(i)));
            }
            it.write(sb.append(end).toString());
            return null;
        });
        in.define("input", (it, a, kw) -> {
            throw new PyError("RuntimeError", "input() недоступен в Кодинг-мире — используйте переменные или события, например on_chat");
        });
        in.define("len", (it, a, kw) -> { argc("len", a, 1, 1); return Py.len(a.get(0)); });
        in.define("range", (it, a, kw) -> {
            argc("range", a, 1, 3);
            long start = 0, stop, step = 1;
            if (a.size() == 1) stop = Py.toLong(a.get(0));
            else {
                start = Py.toLong(a.get(0));
                stop = Py.toLong(a.get(1));
                if (a.size() == 3) step = Py.toLong(a.get(2));
            }
            return new PyRange(start, stop, step);
        });
        in.define("str", (it, a, kw) -> { argc("str", a, 0, 1); return a.isEmpty() ? "" : Py.str(a.get(0)); });
        in.define("repr", (it, a, kw) -> { argc("repr", a, 1, 1); return Py.repr(a.get(0)); });
        in.define("int", (it, a, kw) -> {
            argc("int", a, 0, 2);
            if (a.isEmpty()) return 0L;
            Object v = a.get(0);
            if (v instanceof String s) {
                String t = s.strip().replace("_", "");
                int base = a.size() > 1 ? (int) Py.toLong(a.get(1)) : 10;
                try {
                    if (base == 16 && t.toLowerCase().startsWith("0x")) t = t.substring(2);
                    return Long.parseLong(t, base);
                } catch (NumberFormatException e) {
                    throw new PyError("ValueError", "нельзя превратить в целое число строку " + Py.repr(s));
                }
            }
            if (v instanceof Double d) {
                if (d.isNaN() || d.isInfinite()) throw new PyError("ValueError", "нельзя превратить " + Py.repr(d) + " в int");
                return (long) (double) d;
            }
            if (v instanceof Boolean b) return b ? 1L : 0L;
            if (v instanceof Long) return v;
            throw new PyError("TypeError", "нельзя превратить " + Py.typeName(v) + " в int");
        });
        in.define("float", (it, a, kw) -> {
            argc("float", a, 0, 1);
            if (a.isEmpty()) return 0.0;
            Object v = a.get(0);
            if (v instanceof String s) {
                String t = s.strip().toLowerCase();
                if (t.equals("inf") || t.equals("+inf")) return Double.POSITIVE_INFINITY;
                if (t.equals("-inf")) return Double.NEGATIVE_INFINITY;
                if (t.equals("nan")) return Double.NaN;
                try {
                    return Double.parseDouble(t);
                } catch (NumberFormatException e) {
                    throw new PyError("ValueError", "нельзя превратить в дробное число строку " + Py.repr(s));
                }
            }
            return Py.toDouble(v);
        });
        in.define("bool", (it, a, kw) -> { argc("bool", a, 0, 1); return !a.isEmpty() && Py.truthy(a.get(0)); });
        in.define("list", (it, a, kw) -> { argc("list", a, 0, 1); return a.isEmpty() ? new PyList() : new PyList(Py.toList(a.get(0))); });
        in.define("tuple", (it, a, kw) -> { argc("tuple", a, 0, 1); return a.isEmpty() ? new PyTuple() : new PyTuple(Py.toList(a.get(0))); });
        in.define("set", (it, a, kw) -> {
            argc("set", a, 0, 1);
            PySet s = new PySet();
            if (!a.isEmpty()) for (Object o : Py.toList(a.get(0))) s.add(o);
            return s;
        });
        in.define("dict", (it, a, kw) -> {
            argc("dict", a, 0, 1);
            PyDict d = new PyDict();
            if (!a.isEmpty()) {
                if (a.get(0) instanceof PyDict src) for (Object k : src.keyList()) d.put(k, src.get(k));
                else for (Object item : Py.toList(a.get(0))) {
                    List<Object> p = Py.toList(item);
                    if (p.size() != 2) throw new PyError("ValueError", "dict(): каждый элемент должен быть парой (ключ, значение)");
                    d.put(p.get(0), p.get(1));
                }
            }
            for (var e : kw.entrySet()) d.put(e.getKey(), e.getValue());
            return d;
        });
        in.define("abs", (it, a, kw) -> {
            argc("abs", a, 1, 1);
            Object v = a.get(0);
            if (Py.isInt(v)) return Math.abs(Py.toLong(v));
            return Math.abs(Py.toDouble(v));
        });
        in.define("min", (it, a, kw) -> minMax(it, a, kw, true));
        in.define("max", (it, a, kw) -> minMax(it, a, kw, false));
        in.define("sum", (it, a, kw) -> {
            argc("sum", a, 1, 2);
            Object acc = a.size() > 1 ? a.get(1) : 0L;
            for (Iterator<Object> i = Py.iter(a.get(0)); i.hasNext(); ) acc = Py.binop("+", acc, i.next());
            return acc;
        });
        in.define("round", (it, a, kw) -> {
            argc("round", a, 1, 2);
            Object v = a.get(0);
            if (a.size() == 1 || a.get(1) == null) {
                if (Py.isInt(v)) return Py.toLong(v);
                double d = Py.toDouble(v);
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new PyError("ValueError", "нельзя округлить " + Py.repr(v));
                return (long) Math.rint(d);
            }
            int nd = (int) Py.toLong(a.get(1));
            if (Py.isInt(v) && nd >= 0) return Py.toLong(v);
            double d = Py.toDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) return d;
            return new BigDecimal(d).setScale(nd, RoundingMode.HALF_EVEN).doubleValue();
        });
        in.define("sorted", (it, a, kw) -> {
            argc("sorted", a, 1, 1);
            PyList l = new PyList(Py.toList(a.get(0)));
            Methods.sort(it, l, kw.get("key"), Py.truthy(kw.get("reverse")));
            return l;
        });
        in.define("reversed", (it, a, kw) -> {
            argc("reversed", a, 1, 1);
            PyList l = new PyList(Py.toList(a.get(0)));
            Collections.reverse(l);
            return l;
        });
        in.define("enumerate", (it, a, kw) -> {
            argc("enumerate", a, 1, 2);
            long start = a.size() > 1 ? Py.toLong(a.get(1)) : kw.containsKey("start") ? Py.toLong(kw.get("start")) : 0;
            PyList out = new PyList();
            for (Object o : Py.toList(a.get(0))) out.add(new PyTuple(start++, o));
            return out;
        });
        in.define("zip", (it, a, kw) -> {
            List<List<Object>> lists = new ArrayList<>();
            int n = Integer.MAX_VALUE;
            for (Object o : a) {
                List<Object> l = Py.toList(o);
                lists.add(l);
                n = Math.min(n, l.size());
            }
            PyList out = new PyList();
            if (lists.isEmpty()) return out;
            for (int i = 0; i < n; i++) {
                Object[] row = new Object[lists.size()];
                for (int j = 0; j < lists.size(); j++) row[j] = lists.get(j).get(i);
                out.add(new PyTuple(row));
            }
            return out;
        });
        in.define("map", (it, a, kw) -> {
            argc("map", a, 2, Integer.MAX_VALUE);
            PyList out = new PyList();
            if (a.size() == 2) {
                for (Object o : Py.toList(a.get(1))) out.add(it.callValue(a.get(0), List.of(o)));
                return out;
            }
            List<List<Object>> lists = new ArrayList<>();
            int n = Integer.MAX_VALUE;
            for (int i = 1; i < a.size(); i++) {
                lists.add(Py.toList(a.get(i)));
                n = Math.min(n, lists.get(i - 1).size());
            }
            for (int i = 0; i < n; i++) {
                List<Object> args = new ArrayList<>();
                for (List<Object> l : lists) args.add(l.get(i));
                out.add(it.callValue(a.get(0), args));
            }
            return out;
        });
        in.define("filter", (it, a, kw) -> {
            argc("filter", a, 2, 2);
            PyList out = new PyList();
            for (Object o : Py.toList(a.get(1))) {
                if (a.get(0) == null ? Py.truthy(o) : Py.truthy(it.callValue(a.get(0), List.of(o)))) out.add(o);
            }
            return out;
        });
        in.define("any", (it, a, kw) -> {
            argc("any", a, 1, 1);
            for (Iterator<Object> i = Py.iter(a.get(0)); i.hasNext(); ) if (Py.truthy(i.next())) return true;
            return false;
        });
        in.define("all", (it, a, kw) -> {
            argc("all", a, 1, 1);
            for (Iterator<Object> i = Py.iter(a.get(0)); i.hasNext(); ) if (!Py.truthy(i.next())) return false;
            return true;
        });
        in.define("type", (it, a, kw) -> {
            argc("type", a, 1, 1);
            Object t = it.builtins.get(Py.typeName(a.get(0)));
            return t != null ? t : Py.typeName(a.get(0));
        });
        in.define("isinstance", (it, a, kw) -> {
            argc("isinstance", a, 2, 2);
            List<Object> types = a.get(1) instanceof PyTuple t ? Py.toList(t) : List.of(a.get(1));
            String tn = Py.typeName(a.get(0));
            for (Object t : types) {
                String name = t instanceof Builtin b ? b.name : t instanceof ExcClass c ? c.name : Py.str(t);
                if (name.equals(tn)) return true;
                if (name.equals("int") && tn.equals("bool")) return true;
                if (t instanceof ExcClass c && a.get(0) instanceof PyException pe && pe.cls.isSub(c.name)) return true;
            }
            return false;
        });
        in.define("callable", (it, a, kw) -> { argc("callable", a, 1, 1); return a.get(0) instanceof PyCallable; });
        in.define("chr", (it, a, kw) -> { argc("chr", a, 1, 1); return new String(Character.toChars((int) Py.toLong(a.get(0)))); });
        in.define("ord", (it, a, kw) -> {
            argc("ord", a, 1, 1);
            if (!(a.get(0) instanceof String s) || s.codePointCount(0, s.length()) != 1) throw new PyError("TypeError", "ord() ожидает один символ");
            return (long) s.codePointAt(0);
        });
        in.define("divmod", (it, a, kw) -> { argc("divmod", a, 2, 2); return new PyTuple(Py.binop("//", a.get(0), a.get(1)), Py.binop("%", a.get(0), a.get(1))); });
        in.define("pow", (it, a, kw) -> {
            argc("pow", a, 2, 3);
            if (a.size() == 3) {
                return java.math.BigInteger.valueOf(Py.toLong(a.get(0))).modPow(java.math.BigInteger.valueOf(Py.toLong(a.get(1))), java.math.BigInteger.valueOf(Py.toLong(a.get(2)))).longValue();
            }
            return Py.binop("**", a.get(0), a.get(1));
        });
        in.define("hex", (it, a, kw) -> { argc("hex", a, 1, 1); long v = Py.toLong(a.get(0)); return (v < 0 ? "-0x" : "0x") + Long.toHexString(Math.abs(v)); });
        in.define("bin", (it, a, kw) -> { argc("bin", a, 1, 1); long v = Py.toLong(a.get(0)); return (v < 0 ? "-0b" : "0b") + Long.toBinaryString(Math.abs(v)); });
        in.define("oct", (it, a, kw) -> { argc("oct", a, 1, 1); long v = Py.toLong(a.get(0)); return (v < 0 ? "-0o" : "0o") + Long.toOctalString(Math.abs(v)); });
        in.define("format", (it, a, kw) -> { argc("format", a, 1, 2); return Py.format(a.get(0), a.size() > 1 ? Py.str(a.get(1)) : ""); });
        in.define("id", (it, a, kw) -> { argc("id", a, 1, 1); return (long) System.identityHashCode(a.get(0)); });
        in.define("hash", (it, a, kw) -> { argc("hash", a, 1, 1); return (long) Py.hash(a.get(0)); });
        in.define("exit", (it, a, kw) -> { throw new Interpreter.ScriptStopped(true); });
        in.define("quit", (it, a, kw) -> { throw new Interpreter.ScriptStopped(true); });

        // exceptions
        ExcClass base = new ExcClass("Exception", null);
        in.builtins.put("Exception", base);
        String[][] tree = {
                {"ValueError", "Exception"}, {"TypeError", "Exception"}, {"NameError", "Exception"},
                {"UnboundLocalError", "NameError"}, {"ArithmeticError", "Exception"}, {"ZeroDivisionError", "ArithmeticError"},
                {"OverflowError", "ArithmeticError"}, {"LookupError", "Exception"}, {"IndexError", "LookupError"},
                {"KeyError", "LookupError"}, {"AttributeError", "Exception"}, {"RuntimeError", "Exception"},
                {"RecursionError", "RuntimeError"}, {"NotImplementedError", "RuntimeError"}, {"AssertionError", "Exception"},
                {"ImportError", "Exception"}, {"MemoryError", "Exception"}, {"StopIteration", "Exception"},
                {"WorldError", "Exception"}, {"SyntaxError", "Exception"}, {"IndentationError", "SyntaxError"},
        };
        for (String[] t : tree) in.builtins.put(t[0], new ExcClass(t[0], (ExcClass) in.builtins.get(t[1])));

        installModules(in);
    }

    private static Object minMax(Interpreter it, List<Object> a, Map<String, Object> kw, boolean min) {
        String name = min ? "min" : "max";
        argc(name, a, 1, Integer.MAX_VALUE);
        List<Object> items = a.size() == 1 ? Py.toList(a.get(0)) : a;
        if (items.isEmpty()) {
            if (kw.containsKey("default")) return kw.get("default");
            throw new PyError("ValueError", name + "() от пустого списка");
        }
        Object key = kw.get("key");
        Object best = items.get(0);
        Object bestK = key == null ? best : it.callValue(key, List.of(best));
        for (int i = 1; i < items.size(); i++) {
            Object o = items.get(i);
            Object k = key == null ? o : it.callValue(key, List.of(o));
            int c = Py.compare(k, bestK, min ? "<" : ">");
            if (min ? c < 0 : c > 0) {
                best = o;
                bestK = k;
            }
        }
        return best;
    }

    private static void fn(PyModule m, String name, Native fn) {
        m.attrs.put(name, new Builtin(m.name + "." + name, fn));
    }

    interface D1 { double f(double x); }

    private static void math1(PyModule m, String name, D1 f) {
        fn(m, name, (it, a, kw) -> { argc(name, a, 1, 1); return f.f(Py.toDouble(a.get(0))); });
    }

    private static void installModules(Interpreter in) {
        PyModule math = new PyModule("math");
        math.attrs.put("pi", Math.PI);
        math.attrs.put("e", Math.E);
        math.attrs.put("tau", Math.PI * 2);
        math.attrs.put("inf", Double.POSITIVE_INFINITY);
        math.attrs.put("nan", Double.NaN);
        fn(math, "sqrt", (it, a, kw) -> {
            argc("sqrt", a, 1, 1);
            double v = Py.toDouble(a.get(0));
            if (v < 0) throw new PyError("ValueError", "корень из отрицательного числа");
            return Math.sqrt(v);
        });
        math1(math, "sin", Math::sin);
        math1(math, "cos", Math::cos);
        math1(math, "tan", Math::tan);
        math1(math, "asin", Math::asin);
        math1(math, "acos", Math::acos);
        math1(math, "atan", Math::atan);
        math1(math, "exp", Math::exp);
        math1(math, "fabs", Math::abs);
        math1(math, "log10", Math::log10);
        math1(math, "log2", x -> Math.log(x) / Math.log(2));
        math1(math, "radians", Math::toRadians);
        math1(math, "degrees", Math::toDegrees);
        fn(math, "atan2", (it, a, kw) -> { argc("atan2", a, 2, 2); return Math.atan2(Py.toDouble(a.get(0)), Py.toDouble(a.get(1))); });
        fn(math, "hypot", (it, a, kw) -> {
            double s = 0;
            for (Object o : a) s += Py.toDouble(o) * Py.toDouble(o);
            return Math.sqrt(s);
        });
        fn(math, "pow", (it, a, kw) -> { argc("pow", a, 2, 2); return Math.pow(Py.toDouble(a.get(0)), Py.toDouble(a.get(1))); });
        fn(math, "log", (it, a, kw) -> {
            argc("log", a, 1, 2);
            double x = Py.toDouble(a.get(0));
            if (x <= 0) throw new PyError("ValueError", "логарифм от неположительного числа");
            return a.size() == 1 ? Math.log(x) : Math.log(x) / Math.log(Py.toDouble(a.get(1)));
        });
        fn(math, "floor", (it, a, kw) -> { argc("floor", a, 1, 1); return (long) Math.floor(Py.toDouble(a.get(0))); });
        fn(math, "ceil", (it, a, kw) -> { argc("ceil", a, 1, 1); return (long) Math.ceil(Py.toDouble(a.get(0))); });
        fn(math, "trunc", (it, a, kw) -> { argc("trunc", a, 1, 1); return (long) Py.toDouble(a.get(0)); });
        fn(math, "isqrt", (it, a, kw) -> { argc("isqrt", a, 1, 1); return (long) Math.floor(Math.sqrt(Py.toLong(a.get(0)))); });
        fn(math, "factorial", (it, a, kw) -> {
            argc("factorial", a, 1, 1);
            long n = Py.toLong(a.get(0));
            if (n < 0) throw new PyError("ValueError", "факториал отрицательного числа");
            if (n > 20) throw new PyError("OverflowError", "факториал больше 20! не помещается в число");
            long r = 1;
            for (long i = 2; i <= n; i++) r *= i;
            return r;
        });
        fn(math, "gcd", (it, a, kw) -> {
            argc("gcd", a, 2, 2);
            long x = Math.abs(Py.toLong(a.get(0))), y = Math.abs(Py.toLong(a.get(1)));
            while (y != 0) { long t = x % y; x = y; y = t; }
            return x;
        });
        fn(math, "dist", (it, a, kw) -> {
            argc("dist", a, 2, 2);
            List<Object> p = Py.toList(a.get(0)), q = Py.toList(a.get(1));
            double s = 0;
            for (int i = 0; i < Math.min(p.size(), q.size()); i++) {
                double d = Py.toDouble(p.get(i)) - Py.toDouble(q.get(i));
                s += d * d;
            }
            return Math.sqrt(s);
        });
        fn(math, "isclose", (it, a, kw) -> { argc("isclose", a, 2, 2); return Math.abs(Py.toDouble(a.get(0)) - Py.toDouble(a.get(1))) <= 1e-9 * Math.max(Math.abs(Py.toDouble(a.get(0))), Math.abs(Py.toDouble(a.get(1)))); });
        in.modules.put("math", math);

        PyModule random = new PyModule("random");
        fn(random, "random", (it, a, kw) -> it.random.nextDouble());
        fn(random, "randint", (it, a, kw) -> {
            argc("randint", a, 2, 2);
            long lo = Py.toLong(a.get(0)), hi = Py.toLong(a.get(1));
            if (hi < lo) throw new PyError("ValueError", "randint(a, b): a должно быть не больше b");
            return lo + (long) Math.floor(it.random.nextDouble() * (hi - lo + 1));
        });
        fn(random, "randrange", (it, a, kw) -> {
            argc("randrange", a, 1, 3);
            long lo = a.size() == 1 ? 0 : Py.toLong(a.get(0));
            long hi = a.size() == 1 ? Py.toLong(a.get(0)) : Py.toLong(a.get(1));
            long step = a.size() == 3 ? Py.toLong(a.get(2)) : 1;
            PyRange r = new PyRange(lo, hi, step);
            if (r.size() == 0) throw new PyError("ValueError", "пустой диапазон для randrange");
            return r.get((long) Math.floor(it.random.nextDouble() * r.size()));
        });
        fn(random, "uniform", (it, a, kw) -> {
            argc("uniform", a, 2, 2);
            double lo = Py.toDouble(a.get(0)), hi = Py.toDouble(a.get(1));
            return lo + it.random.nextDouble() * (hi - lo);
        });
        fn(random, "choice", (it, a, kw) -> {
            argc("choice", a, 1, 1);
            List<Object> l = Py.toList(a.get(0));
            if (l.isEmpty()) throw new PyError("IndexError", "choice() из пустого списка");
            return l.get(it.random.nextInt(l.size()));
        });
        fn(random, "shuffle", (it, a, kw) -> {
            argc("shuffle", a, 1, 1);
            if (!(a.get(0) instanceof PyList l)) throw new PyError("TypeError", "shuffle() ожидает список");
            Collections.shuffle(l, it.random);
            return null;
        });
        fn(random, "sample", (it, a, kw) -> {
            argc("sample", a, 2, 2);
            List<Object> l = new ArrayList<>(Py.toList(a.get(0)));
            int k = (int) Py.toLong(a.get(1));
            if (k > l.size() || k < 0) throw new PyError("ValueError", "sample(): k больше размера списка");
            Collections.shuffle(l, it.random);
            return new PyList(l.subList(0, k));
        });
        fn(random, "seed", (it, a, kw) -> {
            it.random.setSeed(a.isEmpty() ? System.nanoTime() : Py.hash(a.get(0)));
            return null;
        });
        in.modules.put("random", random);

        PyModule time = new PyModule("time");
        fn(time, "time", (it, a, kw) -> System.currentTimeMillis() / 1000.0);
        fn(time, "sleep", (it, a, kw) -> {
            argc("sleep", a, 1, 1);
            it.host.sleepTicks(Math.max(0, Math.round(Py.toDouble(a.get(0)) * 20)));
            return null;
        });
        in.modules.put("time", time);
    }
}
