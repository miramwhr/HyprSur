package com.miramwh.codingworld.py;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Runtime value types. Python ints are Long, floats Double, str String, bool Boolean and None is null. */
public final class Values {
    private Values() {}

    public static final class PyList extends ArrayList<Object> {
        public PyList() {}
        public PyList(Collection<?> c) { super(c); }
        @Override public boolean equals(Object o) { return this == o; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    public static final class PyTuple {
        public final Object[] items;
        public PyTuple(Object... items) { this.items = items; }
        public PyTuple(List<?> l) { this.items = l.toArray(); }
        public int size() { return items.length; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PyTuple t) || t.items.length != items.length) return false;
            for (int i = 0; i < items.length; i++) if (!Py.eq(items[i], t.items[i])) return false;
            return true;
        }
        @Override public int hashCode() {
            int h = 7;
            for (Object o : items) h = h * 31 + Py.hash(o);
            return h;
        }
    }

    /** Dict key wrapper so that 1 and 1.0 (and True) are the same key, like in Python. */
    static Object key(Object k) {
        if (k instanceof Boolean b) return b ? 1L : 0L;
        if (k instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9e18) return d.longValue();
        if (k instanceof PyList || k instanceof PyDict || k instanceof PySet)
            throw new PyError("TypeError", "список/словарь нельзя использовать как ключ словаря");
        return k;
    }

    public static final class PyDict {
        public final LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        /** Original keys (so True stays True when printed). */
        public final LinkedHashMap<Object, Object> keys = new LinkedHashMap<>();
        public Object get(Object k) { return map.get(key(k)); }
        public boolean has(Object k) { return map.containsKey(key(k)); }
        public void put(Object k, Object v) {
            Object kk = key(k);
            if (!map.containsKey(kk)) keys.put(kk, k);
            map.put(kk, v);
        }
        public Object remove(Object k) { Object kk = key(k); keys.remove(kk); return map.remove(kk); }
        public int size() { return map.size(); }
        public List<Object> keyList() { return new ArrayList<>(keys.values()); }
        public void clear() { map.clear(); keys.clear(); }
    }

    public static final class PySet {
        public final LinkedHashSet<Object> set = new LinkedHashSet<>();
        public boolean add(Object o) { return set.add(key(o)); }
        public boolean has(Object o) { return set.contains(key(o)); }
        public boolean remove(Object o) { return set.remove(key(o)); }
    }

    public static final class PyRange {
        public final long start, stop, step;
        public PyRange(long start, long stop, long step) {
            if (step == 0) throw new PyError("ValueError", "шаг range() не может быть 0");
            this.start = start; this.stop = stop; this.step = step;
        }
        public long size() {
            if (step > 0) return stop > start ? (stop - start + step - 1) / step : 0;
            return start > stop ? (start - stop - step - 1) / (-step) : 0;
        }
        public long get(long i) { return start + i * step; }
        public Iterator<Object> iterator() {
            return new Iterator<>() {
                long cur = start;
                public boolean hasNext() { return step > 0 ? cur < stop : cur > stop; }
                public Object next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    long v = cur;
                    cur += step;
                    return v;
                }
            };
        }
    }

    public interface PyCallable {
        Object call(Interpreter in, List<Object> args, Map<String, Object> kwargs, int line);
        String name();
    }

    public static final class PyFunction implements PyCallable {
        final String name;
        final Node.Params params;
        final List<Object> defaults;
        final List<Node> body;
        final Node exprBody;
        final java.util.Set<String> locals;
        final Interpreter.Env closure;
        PyFunction(String name, Node.Params params, List<Object> defaults, List<Node> body, Node exprBody, java.util.Set<String> locals, Interpreter.Env closure) {
            this.name = name; this.params = params; this.defaults = defaults; this.body = body; this.exprBody = exprBody; this.locals = locals; this.closure = closure;
        }
        @Override public Object call(Interpreter in, List<Object> args, Map<String, Object> kwargs, int line) { return in.callFunction(this, args, kwargs, line); }
        @Override public String name() { return name; }
        public int paramCount() { return params.names.size(); }
        public boolean hasVarArgs() { return params.varArgs != null; }
    }

    /** A Java-implemented function. */
    public interface Native {
        Object call(Interpreter in, List<Object> args, Map<String, Object> kw);
    }

    public static final class Builtin implements PyCallable {
        final String name;
        final Native fn;
        public Builtin(String name, Native fn) { this.name = name; this.fn = fn; }
        @Override public Object call(Interpreter in, List<Object> args, Map<String, Object> kwargs, int line) { return fn.call(in, args, kwargs); }
        @Override public String name() { return name; }
    }

    public static final class BoundMethod implements PyCallable {
        final Object self;
        final String name;
        BoundMethod(Object self, String name) { this.self = self; this.name = name; }
        @Override public Object call(Interpreter in, List<Object> args, Map<String, Object> kwargs, int line) { return Methods.call(in, self, name, args, kwargs); }
        @Override public String name() { return name; }
    }

    public static final class PyModule {
        final String name;
        final Map<String, Object> attrs = new LinkedHashMap<>();
        PyModule(String name) { this.name = name; }
    }

    /** Exception class like ValueError; calling it creates a PyException. */
    public static final class ExcClass implements PyCallable {
        final String name;
        final ExcClass parent;
        ExcClass(String name, ExcClass parent) { this.name = name; this.parent = parent; }
        public boolean isSub(String other) {
            for (ExcClass c = this; c != null; c = c.parent) if (c.name.equals(other)) return true;
            return false;
        }
        @Override public Object call(Interpreter in, List<Object> args, Map<String, Object> kwargs, int line) {
            return new PyException(this, args.isEmpty() ? "" : Py.str(args.get(0)), new PyTuple(args));
        }
        @Override public String name() { return name; }
    }

    public static final class PyException {
        final ExcClass cls;
        final String message;
        final PyTuple args;
        PyException(ExcClass cls, String message, PyTuple args) { this.cls = cls; this.message = message; this.args = args; }
    }
}
