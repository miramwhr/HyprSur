package com.miramwh.codingworld.py;

import com.miramwh.codingworld.py.Values.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Methods of built-in types: str, list, dict, set. */
public final class Methods {
    private Methods() {}

    static final Set<String> STR = Set.of("upper", "lower", "strip", "lstrip", "rstrip", "split", "join", "replace",
            "startswith", "endswith", "find", "rfind", "index", "count", "format", "isdigit", "isalpha", "isalnum", "isspace",
            "isupper", "islower", "title", "capitalize", "center", "ljust", "rjust", "zfill", "splitlines", "swapcase", "partition");
    static final Set<String> LIST = Set.of("append", "extend", "insert", "pop", "remove", "index", "count", "sort",
            "reverse", "clear", "copy");
    static final Set<String> DICT = Set.of("keys", "values", "items", "get", "pop", "update", "setdefault", "clear", "copy", "popitem");
    static final Set<String> SET = Set.of("add", "remove", "discard", "pop", "clear", "copy", "union", "intersection",
            "difference", "issubset", "issuperset", "update");

    public static boolean has(Object self, String name) {
        if (self instanceof String) return STR.contains(name);
        if (self instanceof PyList) return LIST.contains(name);
        if (self instanceof PyTuple) return name.equals("index") || name.equals("count");
        if (self instanceof PyDict) return DICT.contains(name);
        if (self instanceof PySet) return SET.contains(name);
        return false;
    }

    private static void argc(String name, List<Object> a, int min, int max) {
        if (a.size() < min || a.size() > max) {
            String exp = min == max ? String.valueOf(min) : min + "–" + max;
            throw new PyError("TypeError", name + "() ожидает " + exp + " аргумент(ов), получено " + a.size());
        }
    }

    private static String s(Object o, String what) {
        if (o instanceof String s) return s;
        throw new PyError("TypeError", what + ": ожидалась строка, а получено " + Py.typeName(o));
    }

    public static Object call(Interpreter in, Object self, String name, List<Object> a, Map<String, Object> kw) {
        if (self instanceof String str) return strMethod(in, str, name, a, kw);
        if (self instanceof PyList l) return listMethod(in, l, name, a, kw);
        if (self instanceof PyTuple t) {
            argc(name, a, 1, 1);
            List<Object> items = Py.toList(t);
            if (name.equals("count")) return (long) items.stream().filter(x -> Py.eq(x, a.get(0))).count();
            for (int i = 0; i < items.size(); i++) if (Py.eq(items.get(i), a.get(0))) return (long) i;
            throw new PyError("ValueError", Py.repr(a.get(0)) + " нет в кортеже");
        }
        if (self instanceof PyDict d) return dictMethod(d, name, a, kw);
        if (self instanceof PySet st) return setMethod(st, name, a);
        throw new PyError("AttributeError", "у " + Py.typeName(self) + " нет метода " + name);
    }

    private static Object strMethod(Interpreter in, String s, String name, List<Object> a, Map<String, Object> kw) {
        switch (name) {
            case "upper": argc(name, a, 0, 0); return s.toUpperCase();
            case "lower": argc(name, a, 0, 0); return s.toLowerCase();
            case "swapcase": {
                StringBuilder sb = new StringBuilder();
                for (char c : s.toCharArray()) sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
                return sb.toString();
            }
            case "strip": case "lstrip": case "rstrip": {
                argc(name, a, 0, 1);
                String chars = a.isEmpty() || a.get(0) == null ? null : s(a.get(0), name);
                int b = 0, e = s.length();
                if (!name.equals("rstrip")) while (b < e && strip(s.charAt(b), chars)) b++;
                if (!name.equals("lstrip")) while (e > b && strip(s.charAt(e - 1), chars)) e--;
                return s.substring(b, e);
            }
            case "split": {
                argc(name, a, 0, 2);
                Object sepO = a.isEmpty() ? kw.get("sep") : a.get(0);
                long max = a.size() > 1 ? Py.toLong(a.get(1)) : kw.containsKey("maxsplit") ? Py.toLong(kw.get("maxsplit")) : -1;
                PyList out = new PyList();
                if (sepO == null) {
                    String t = s.strip();
                    if (t.isEmpty()) return out;
                    String[] parts = max < 0 ? t.split("\\s+") : t.split("\\s+", (int) max + 1);
                    Collections.addAll(out, (Object[]) parts);
                    return out;
                }
                String sep = s(sepO, name);
                if (sep.isEmpty()) throw new PyError("ValueError", "пустой разделитель");
                int from = 0;
                while (max < 0 || out.size() < max) {
                    int i = s.indexOf(sep, from);
                    if (i < 0) break;
                    out.add(s.substring(from, i));
                    from = i + sep.length();
                }
                out.add(s.substring(from));
                return out;
            }
            case "splitlines": {
                PyList out = new PyList();
                if (s.isEmpty()) return out;
                Collections.addAll(out, (Object[]) s.split("\n", -1));
                if (s.endsWith("\n")) out.remove(out.size() - 1);
                return out;
            }
            case "join": {
                argc(name, a, 1, 1);
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object o : Py.toList(a.get(0))) {
                    if (!first) sb.append(s);
                    first = false;
                    if (!(o instanceof String)) throw new PyError("TypeError", "join(): все элементы должны быть строками, а найдено " + Py.typeName(o) + " (используйте str())");
                    sb.append((String) o);
                }
                return sb.toString();
            }
            case "replace": {
                argc(name, a, 2, 3);
                String from = s(a.get(0), name), to = s(a.get(1), name);
                if (a.size() == 3) {
                    long n = Py.toLong(a.get(2));
                    String r = s;
                    StringBuilder sb = new StringBuilder();
                    int idx = 0;
                    for (long k = 0; k < n; k++) {
                        int j = r.indexOf(from, idx);
                        if (j < 0) break;
                        sb.append(r, idx, j).append(to);
                        idx = j + from.length();
                    }
                    return sb.append(r.substring(idx)).toString();
                }
                return s.replace(from, to);
            }
            case "startswith": argc(name, a, 1, 1); return startsEnds(s, a.get(0), true);
            case "endswith": argc(name, a, 1, 1); return startsEnds(s, a.get(0), false);
            case "find": argc(name, a, 1, 2); return (long) s.indexOf(s(a.get(0), name), a.size() > 1 ? (int) Py.toLong(a.get(1)) : 0);
            case "rfind": argc(name, a, 1, 1); return (long) s.lastIndexOf(s(a.get(0), name));
            case "index": {
                argc(name, a, 1, 2);
                int i = s.indexOf(s(a.get(0), name), a.size() > 1 ? (int) Py.toLong(a.get(1)) : 0);
                if (i < 0) throw new PyError("ValueError", "подстрока не найдена");
                return (long) i;
            }
            case "count": {
                argc(name, a, 1, 1);
                String sub = s(a.get(0), name);
                if (sub.isEmpty()) return (long) s.length() + 1;
                long c = 0;
                int i = 0;
                while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
                return c;
            }
            case "format": return strFormat(s, a, kw);
            case "isdigit": return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
            case "isalpha": return !s.isEmpty() && s.chars().allMatch(Character::isLetter);
            case "isalnum": return !s.isEmpty() && s.chars().allMatch(Character::isLetterOrDigit);
            case "isspace": return !s.isEmpty() && s.chars().allMatch(Character::isWhitespace);
            case "isupper": return s.chars().anyMatch(Character::isLetter) && s.equals(s.toUpperCase());
            case "islower": return s.chars().anyMatch(Character::isLetter) && s.equals(s.toLowerCase());
            case "title": {
                StringBuilder sb = new StringBuilder();
                boolean up = true;
                for (char c : s.toCharArray()) {
                    sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                    up = !Character.isLetter(c);
                }
                return sb.toString();
            }
            case "capitalize": return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
            case "center": case "ljust": case "rjust": {
                argc(name, a, 1, 2);
                int w = (int) Py.toLong(a.get(0));
                String f = a.size() > 1 ? s(a.get(1), name) : " ";
                return Py.format(s, f + (name.equals("center") ? "^" : name.equals("ljust") ? "<" : ">") + w);
            }
            case "zfill": {
                argc(name, a, 1, 1);
                int w = (int) Py.toLong(a.get(0));
                boolean neg = s.startsWith("-");
                String body = neg ? s.substring(1) : s;
                while (body.length() + (neg ? 1 : 0) < w) body = "0" + body;
                return (neg ? "-" : "") + body;
            }
            case "partition": {
                argc(name, a, 1, 1);
                String sep = s(a.get(0), name);
                int i = s.indexOf(sep);
                if (i < 0) return new PyTuple(s, "", "");
                return new PyTuple(s.substring(0, i), sep, s.substring(i + sep.length()));
            }
            default: throw new PyError("AttributeError", "у строки нет метода " + name);
        }
    }

    private static boolean strip(char c, String chars) {
        return chars == null ? Character.isWhitespace(c) : chars.indexOf(c) >= 0;
    }

    private static boolean startsEnds(String s, Object arg, boolean start) {
        if (arg instanceof PyTuple t) {
            for (Object o : t.items) if (start ? s.startsWith(s(o, "startswith")) : s.endsWith(s(o, "endswith"))) return true;
            return false;
        }
        String p = s(arg, start ? "startswith" : "endswith");
        return start ? s.startsWith(p) : s.endsWith(p);
    }

    static String strFormat(String s, List<Object> a, Map<String, Object> kw) {
        StringBuilder sb = new StringBuilder();
        int auto = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '{') { sb.append('{'); i++; continue; }
                int j = s.indexOf('}', i);
                if (j < 0) throw new PyError("ValueError", "в format() не закрыта фигурная скобка");
                String field = s.substring(i + 1, j);
                String spec = "";
                int colon = field.indexOf(':');
                if (colon >= 0) { spec = field.substring(colon + 1); field = field.substring(0, colon); }
                boolean repr = false;
                if (field.endsWith("!r")) { repr = true; field = field.substring(0, field.length() - 2); }
                else if (field.endsWith("!s")) field = field.substring(0, field.length() - 2);
                Object v;
                if (field.isEmpty()) {
                    if (auto >= a.size()) throw new PyError("IndexError", "format(): не хватает аргументов");
                    v = a.get(auto++);
                } else if (field.chars().allMatch(Character::isDigit)) {
                    int k = Integer.parseInt(field);
                    if (k >= a.size()) throw new PyError("IndexError", "format(): нет аргумента номер " + k);
                    v = a.get(k);
                } else {
                    if (!kw.containsKey(field)) throw new PyError("KeyError", "format(): нет именованного аргумента '" + field + "'");
                    v = kw.get(field);
                }
                sb.append(repr ? Py.repr(v) : Py.format(v, spec));
                i = j;
            } else if (c == '}') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '}') i++;
                sb.append('}');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String percentFormat(String s, Object args) {
        List<Object> a = args instanceof PyTuple t ? Py.toList(t) : List.of(args == null ? "None" : args);
        List<Object> list = new ArrayList<>(a);
        if (args == null) { list.clear(); list.add(null); }
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '%') { sb.append(c); continue; }
            if (i + 1 < s.length() && s.charAt(i + 1) == '%') { sb.append('%'); i++; continue; }
            int j = i + 1;
            while (j < s.length() && "0123456789.-+ ".indexOf(s.charAt(j)) >= 0) j++;
            if (j >= s.length()) throw new PyError("ValueError", "незаконченный формат %");
            String flags = s.substring(i + 1, j);
            char type = s.charAt(j);
            if (k >= list.size()) throw new PyError("TypeError", "не хватает значений для форматирования %");
            Object v = list.get(k++);
            String spec = flags.startsWith("-") ? "<" + flags.substring(1) : flags;
            switch (type) {
                case 's' -> sb.append(Py.format(Py.str(v), spec));
                case 'r' -> sb.append(Py.repr(v));
                case 'd', 'i' -> sb.append(Py.format((long) Py.toDouble(v), spec + "d"));
                case 'f' -> sb.append(Py.format(v, spec + "f"));
                case 'x' -> sb.append(Py.format(v, spec + "x"));
                default -> throw new PyError("ValueError", "неизвестный формат %" + type);
            }
            i = j;
        }
        if (k < list.size()) throw new PyError("TypeError", "лишние значения при форматировании %");
        return sb.toString();
    }

    private static Object listMethod(Interpreter in, PyList l, String name, List<Object> a, Map<String, Object> kw) {
        switch (name) {
            case "append":
                argc(name, a, 1, 1);
                if (l.size() >= Py.MAX_SIZE) throw new PyError("MemoryError", "список слишком большой");
                l.add(a.get(0));
                return null;
            case "extend":
                argc(name, a, 1, 1);
                l.addAll(Py.toList(a.get(0)));
                if (l.size() > Py.MAX_SIZE) throw new PyError("MemoryError", "список слишком большой");
                return null;
            case "insert": {
                argc(name, a, 2, 2);
                long i = Py.toLong(a.get(0));
                if (i < 0) i += l.size();
                l.add((int) Math.max(0, Math.min(l.size(), i)), a.get(1));
                return null;
            }
            case "pop": {
                argc(name, a, 0, 1);
                if (l.isEmpty()) throw new PyError("IndexError", "pop() из пустого списка");
                long i = a.isEmpty() ? l.size() - 1 : Py.toLong(a.get(0));
                if (i < 0) i += l.size();
                if (i < 0 || i >= l.size()) throw new PyError("IndexError", "индекс pop() вне списка");
                return l.remove((int) i);
            }
            case "remove":
                argc(name, a, 1, 1);
                for (int i = 0; i < l.size(); i++) if (Py.eq(l.get(i), a.get(0))) { l.remove(i); return null; }
                throw new PyError("ValueError", Py.repr(a.get(0)) + " нет в списке");
            case "index":
                argc(name, a, 1, 1);
                for (int i = 0; i < l.size(); i++) if (Py.eq(l.get(i), a.get(0))) return (long) i;
                throw new PyError("ValueError", Py.repr(a.get(0)) + " нет в списке");
            case "count":
                argc(name, a, 1, 1);
                return (long) l.stream().filter(x -> Py.eq(x, a.get(0))).count();
            case "sort":
                argc(name, a, 0, 0);
                sort(in, l, kw.get("key"), Py.truthy(kw.get("reverse")));
                return null;
            case "reverse": Collections.reverse(l); return null;
            case "clear": l.clear(); return null;
            case "copy": return new PyList(l);
            default: throw new PyError("AttributeError", "у списка нет метода " + name);
        }
    }

    static void sort(Interpreter in, List<Object> l, Object key, boolean reverse) {
        Comparator<Object> cmp;
        if (key != null) {
            List<Object[]> pairs = new ArrayList<>();
            for (Object o : l) pairs.add(new Object[]{in.callValue(key, List.of(o)), o});
            pairs.sort((x, y) -> Py.compare(x[0], y[0], "<"));
            if (reverse) Collections.reverse(pairs);
            l.clear();
            for (Object[] p : pairs) l.add(p[1]);
            return;
        }
        cmp = (x, y) -> Py.compare(x, y, "<");
        l.sort(reverse ? cmp.reversed() : cmp);
    }

    private static Object dictMethod(PyDict d, String name, List<Object> a, Map<String, Object> kw) {
        switch (name) {
            case "keys": return new PyList(d.keyList());
            case "values": return new PyList(d.map.values());
            case "items": {
                PyList out = new PyList();
                for (Object k : d.keyList()) out.add(new PyTuple(k, d.get(k)));
                return out;
            }
            case "get": argc(name, a, 1, 2); return d.has(a.get(0)) ? d.get(a.get(0)) : (a.size() > 1 ? a.get(1) : null);
            case "pop": {
                argc(name, a, 1, 2);
                if (d.has(a.get(0))) return d.remove(a.get(0));
                if (a.size() > 1) return a.get(1);
                throw new PyError("KeyError", "нет ключа " + Py.repr(a.get(0)));
            }
            case "popitem": {
                if (d.size() == 0) throw new PyError("KeyError", "словарь пуст");
                List<Object> keys = d.keyList();
                Object k = keys.get(keys.size() - 1);
                return new PyTuple(k, d.remove(k));
            }
            case "update": {
                argc(name, a, 0, 1);
                if (!a.isEmpty()) {
                    if (a.get(0) instanceof PyDict o) for (Object k : o.keyList()) d.put(k, o.get(k));
                    else for (Object it : Py.toList(a.get(0))) { List<Object> p = Py.toList(it); d.put(p.get(0), p.get(1)); }
                }
                for (var e : kw.entrySet()) d.put(e.getKey(), e.getValue());
                return null;
            }
            case "setdefault": {
                argc(name, a, 1, 2);
                if (!d.has(a.get(0))) d.put(a.get(0), a.size() > 1 ? a.get(1) : null);
                return d.get(a.get(0));
            }
            case "clear": d.clear(); return null;
            case "copy": {
                PyDict c = new PyDict();
                for (Object k : d.keyList()) c.put(k, d.get(k));
                return c;
            }
            default: throw new PyError("AttributeError", "у словаря нет метода " + name);
        }
    }

    private static Object setMethod(PySet s, String name, List<Object> a) {
        switch (name) {
            case "add": argc(name, a, 1, 1); s.add(a.get(0)); return null;
            case "remove":
                argc(name, a, 1, 1);
                if (!s.remove(a.get(0))) throw new PyError("KeyError", Py.repr(a.get(0)) + " нет в множестве");
                return null;
            case "discard": argc(name, a, 1, 1); s.remove(a.get(0)); return null;
            case "pop": {
                if (s.set.isEmpty()) throw new PyError("KeyError", "множество пусто");
                Object o = s.set.iterator().next();
                s.set.remove(o);
                return o;
            }
            case "clear": s.set.clear(); return null;
            case "copy": { PySet c = new PySet(); c.set.addAll(s.set); return c; }
            case "update": { for (Object o : a) for (Object x : Py.toList(o)) s.add(x); return null; }
            case "union": { PySet c = new PySet(); c.set.addAll(s.set); for (Object o : a) for (Object x : Py.toList(o)) c.add(x); return c; }
            case "intersection": {
                argc(name, a, 1, 1);
                PySet other = new PySet();
                for (Object x : Py.toList(a.get(0))) other.add(x);
                PySet c = new PySet();
                for (Object x : s.set) if (other.set.contains(x)) c.set.add(x);
                return c;
            }
            case "difference": {
                argc(name, a, 1, 1);
                PySet other = new PySet();
                for (Object x : Py.toList(a.get(0))) other.add(x);
                PySet c = new PySet();
                for (Object x : s.set) if (!other.set.contains(x)) c.set.add(x);
                return c;
            }
            case "issubset": { argc(name, a, 1, 1); for (Object x : s.set) if (!Py.contains(a.get(0), x)) return false; return true; }
            case "issuperset": { argc(name, a, 1, 1); for (Object x : Py.toList(a.get(0))) if (!s.has(x)) return false; return true; }
            default: throw new PyError("AttributeError", "у множества нет метода " + name);
        }
    }
}
