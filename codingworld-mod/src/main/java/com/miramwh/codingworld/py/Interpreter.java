package com.miramwh.codingworld.py;

import com.miramwh.codingworld.py.Node.*;
import com.miramwh.codingworld.py.Values.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tree-walking interpreter. One instance runs one script on its own thread. */
public final class Interpreter {
    /** What the interpreter needs from the outside world. */
    public interface Host {
        void print(String line, boolean error);
        /** Called before every statement; throws ScriptStopped when the script must end. */
        void checkAlive();
        /** Blocks the script for the given number of game ticks. */
        void sleepTicks(long ticks);
        /** Value of a live variable such as PlayerX, or NO_VALUE. */
        default Object dynamic(String name) { return NO_VALUE; }
        default Collection<String> dynamicNames() { return List.of(); }
    }

    /** Thrown to end the script (Stop button, exit(), player left). Not catchable by try/except. */
    public static final class ScriptStopped extends RuntimeException {
        public final boolean normal;
        public ScriptStopped(boolean normal) {
            super(null, null, false, false);
            this.normal = normal;
        }
    }

    public static final Object NO_VALUE = new Object();
    public static final int MAX_DEPTH = 250;

    private static final class BreakEx extends RuntimeException { BreakEx() { super(null, null, false, false); } }
    private static final class ContinueEx extends RuntimeException { ContinueEx() { super(null, null, false, false); } }
    private static final class ReturnEx extends RuntimeException {
        final Object value;
        ReturnEx(Object value) { super(null, null, false, false); this.value = value; }
    }
    private static final BreakEx BREAK = new BreakEx();
    private static final ContinueEx CONTINUE = new ContinueEx();

    public static final class Env {
        final HashMap<String, Object> vars = new HashMap<>();
        final Env parent;
        final Set<String> locals;
        Set<String> globals, nonlocals;
        Env(Env parent, Set<String> locals) { this.parent = parent; this.locals = locals; }
        public Map<String, Object> vars() { return vars; }
    }

    public final Host host;
    public final Map<String, Object> builtins = new LinkedHashMap<>();
    public final Map<String, PyModule> modules = new HashMap<>();
    public final Env globals = new Env(null, null);
    public final java.util.Random random = new java.util.Random();
    private final StringBuilder pendingOut = new StringBuilder();
    private int depth = 0;
    public long steps = 0;

    public Interpreter(Host host) {
        this.host = host;
        Builtins.install(this);
    }

    public void define(String name, Native fn) {
        builtins.put(name, new Builtin(name, fn));
    }

    // ---------- running ----------
    public void run(String source) {
        List<Node> program = Parser.parse(source);
        try {
            execBlock(program, globals);
        } catch (BreakEx | ContinueEx e) {
            throw new PyError("SyntaxError", "break/continue вне цикла");
        } catch (ReturnEx e) {
            throw new PyError("SyntaxError", "return вне функции");
        } finally {
            flush();
        }
    }

    public void write(String s) {
        pendingOut.append(s);
        int nl;
        while ((nl = pendingOut.indexOf("\n")) >= 0) {
            host.print(pendingOut.substring(0, nl), false);
            pendingOut.delete(0, nl + 1);
        }
        if (pendingOut.length() > 4000) flush();
    }

    public void flush() {
        if (pendingOut.length() > 0) {
            host.print(pendingOut.toString(), false);
            pendingOut.setLength(0);
        }
    }

    /** Calls any Python callable from Java (event handlers, sort keys, map, ...). */
    public Object callValue(Object fn, List<Object> args) {
        return callValue(fn, args, Map.of(), 0);
    }

    public Object callValue(Object fn, List<Object> args, Map<String, Object> kwargs, int line) {
        if (fn instanceof PyCallable c) return c.call(this, args, kwargs, line);
        throw new PyError("TypeError", "'" + Py.typeName(fn) + "' нельзя вызвать как функцию" +
                (Py.isNum(fn) ? " (может, пропущен знак * между числом и скобкой?)" : ""));
    }

    // ---------- statements ----------
    private void execBlock(List<Node> body, Env env) {
        for (Node s : body) exec(s, env);
    }

    private void exec(Node s, Env env) {
        steps++;
        host.checkAlive();
        try {
            execInner(s, env);
        } catch (PyError e) {
            throw e.at(s.line);
        } catch (StackOverflowError e) {
            throw new PyError("RecursionError", "слишком глубокая рекурсия", s.line);
        } catch (ArithmeticException | IndexOutOfBoundsException | ClassCastException | NullPointerException | IllegalArgumentException | IllegalStateException e) {
            throw new PyError("RuntimeError", "внутренняя ошибка: " + e, s.line);
        }
    }

    private void execInner(Node s, Env env) {
        if (s instanceof ExprStmt es) {
            eval(es.expr, env);
        } else if (s instanceof Assign a) {
            Object v = eval(a.value, env);
            for (Node t : a.targets) assign(t, v, env);
        } else if (s instanceof AugAssign a) {
            Object cur;
            if (a.target instanceof Index ix) {
                Object obj = eval(ix.obj, env);
                Object idx = evalIndex(ix.index, env);
                cur = Py.getItem(obj, idx);
                Object v = augOp(a.op, cur, eval(a.value, env));
                Py.setItem(obj, idx, v);
                return;
            }
            cur = eval(a.target, env);
            assign(a.target, augOp(a.op, cur, eval(a.value, env)), env);
        } else if (s instanceof If i) {
            if (Py.truthy(eval(i.cond, env))) execBlock(i.body, env);
            else if (i.orElse != null) execBlock(i.orElse, env);
        } else if (s instanceof While w) {
            boolean broke = false;
            while (Py.truthy(eval(w.cond, env))) {
                host.checkAlive();
                try {
                    execBlock(w.body, env);
                } catch (BreakEx b) {
                    broke = true;
                    break;
                } catch (ContinueEx c) {
                    // next iteration
                }
            }
            if (!broke && w.orElse != null) execBlock(w.orElse, env);
        } else if (s instanceof For f) {
            Object it = eval(f.iter, env);
            boolean broke = false;
            for (Iterator<Object> iter = Py.iter(it); iter.hasNext(); ) {
                assign(f.target, iter.next(), env);
                try {
                    execBlock(f.body, env);
                } catch (BreakEx b) {
                    broke = true;
                    break;
                } catch (ContinueEx c) {
                    // next iteration
                }
            }
            if (!broke && f.orElse != null) execBlock(f.orElse, env);
        } else if (s instanceof Def d) {
            Object fn = makeFunction(d.name, d.params, d.body, null, d.locals, env);
            for (int i = d.decorators.size() - 1; i >= 0; i--) {
                fn = callValue(eval(d.decorators.get(i), env), List.of(fn), Map.of(), d.line);
            }
            setName(d.name, fn, env);
        } else if (s instanceof Return r) {
            if (env == globals) throw new PyError("SyntaxError", "return вне функции");
            throw new ReturnEx(r.value == null ? null : eval(r.value, env));
        } else if (s instanceof Simple sm) {
            switch (sm.kind) {
                case "break" -> throw BREAK;
                case "continue" -> throw CONTINUE;
                default -> { }
            }
        } else if (s instanceof Global g) {
            if (env == globals) return;
            if (g.nonlocal) {
                if (env.nonlocals == null) env.nonlocals = new HashSet<>();
                env.nonlocals.addAll(g.names);
            } else {
                if (env.globals == null) env.globals = new HashSet<>();
                env.globals.addAll(g.names);
            }
        } else if (s instanceof Try t) {
            execTry(t, env);
        } else if (s instanceof Raise r) {
            if (r.exc == null) {
                if (currentException != null) throw currentException;
                throw new PyError("RuntimeError", "нет активного исключения для повторного raise");
            }
            Object v = eval(r.exc, env);
            if (v instanceof ExcClass c) v = c.call(this, List.of(), Map.of(), s.line);
            if (v instanceof PyException pe) {
                PyError err = new PyError(pe.cls.name, pe.message, s.line);
                err.value = pe;
                throw err;
            }
            throw new PyError("TypeError", "raise ожидает исключение, например raise ValueError(\"текст\")");
        } else if (s instanceof Del d) {
            for (Node t : d.targets) delete(t, env);
        } else if (s instanceof Import im) {
            execImport(im, env);
        } else if (s instanceof Assert a) {
            if (!Py.truthy(eval(a.cond, env)))
                throw new PyError("AssertionError", a.msg == null ? "условие не выполнено" : Py.str(eval(a.msg, env)));
        } else {
            throw new PyError("SyntaxError", "неподдерживаемая конструкция");
        }
    }

    private PyError currentException;

    private void execTry(Try t, Env env) {
        try {
            try {
                execBlock(t.body, env);
            } catch (PyError e) {
                ExcClass cls = builtins.get(e.type) instanceof ExcClass c ? c : (ExcClass) builtins.get("Exception");
                for (Handler h : t.handlers) {
                    boolean match = h.types.isEmpty();
                    for (String tn : h.types) {
                        if (tn.equals("BaseException") || cls.isSub(tn)) { match = true; break; }
                        if (!(builtins.get(tn) instanceof ExcClass) && !env.vars.containsKey(tn))
                            throw new PyError("NameError", "неизвестный тип исключения '" + tn + "'", h.line);
                    }
                    if (!match) continue;
                    if (h.asName != null) {
                        Object ev = e.value != null ? e.value : new PyException(cls, e.msg, new PyTuple(e.msg));
                        setName(h.asName, ev, env);
                    }
                    PyError saved = currentException;
                    currentException = e;
                    try {
                        execBlock(h.body, env);
                    } finally {
                        currentException = saved;
                    }
                    return;
                }
                throw e;
            }
            if (t.orElse != null) execBlock(t.orElse, env);
        } finally {
            if (t.finallyBody != null) execBlock(t.finallyBody, env);
        }
    }

    private void execImport(Import im, Env env) {
        String root = im.module.split("\\.")[0];
        PyModule m = modules.get(im.module);
        if (m == null) {
            throw new PyError("ImportError", "модуль '" + im.module + "' недоступен. Доступны: " + String.join(", ", modules.keySet())
                    + ". Функции мира (SetBlock и другие) доступны и без импорта.");
        }
        if (im.names == null) {
            setName(im.asName != null ? im.asName : root, m, env);
            return;
        }
        for (int i = 0; i < im.names.size(); i++) {
            String n = im.names.get(i);
            if (n.equals("*")) {
                for (var e : m.attrs.entrySet()) setName(e.getKey(), e.getValue(), env);
                continue;
            }
            if (!m.attrs.containsKey(n)) throw new PyError("ImportError", "в модуле " + im.module + " нет '" + n + "'");
            setName(im.asNames.get(i), m.attrs.get(n), env);
        }
    }

    private Object augOp(String op, Object cur, Object v) {
        if (op.equals("+") && cur instanceof PyList l) {
            l.addAll(Py.toList(v));
            return l;
        }
        return Py.binop(op, cur, v);
    }

    // ---------- names ----------
    private void setName(String name, Object v, Env env) {
        if (env != globals) {
            if (env.globals != null && env.globals.contains(name)) {
                globals.vars.put(name, v);
                return;
            }
            if (env.nonlocals != null && env.nonlocals.contains(name)) {
                for (Env e = env.parent; e != null && e != globals; e = e.parent) {
                    if (e.vars.containsKey(name)) {
                        e.vars.put(name, v);
                        return;
                    }
                }
                throw new PyError("SyntaxError", "nonlocal: переменная '" + name + "' не найдена во внешней функции");
            }
        }
        env.vars.put(name, v);
    }

    private Object lookup(String name, Env env) {
        Env e = env;
        if (e != globals && e.globals != null && e.globals.contains(name)) e = globals;
        boolean first = true;
        for (; e != null; e = e.parent) {
            Object v = e.vars.get(name);
            if (v != null || e.vars.containsKey(name)) return v;
            if (first && e.locals != null && e.locals.contains(name) && (e.nonlocals == null || !e.nonlocals.contains(name)))
                throw new PyError("UnboundLocalError", "переменная '" + name + "' используется до присваивания в функции" +
                        (globals.vars.containsKey(name) ? " (чтобы менять глобальную переменную, напишите в начале функции: global " + name + ")" : ""));
            first = false;
        }
        Object d = host.dynamic(name);
        if (d != NO_VALUE) return d;
        Object b = builtins.get(name);
        if (b != null || builtins.containsKey(name)) return b;
        throw new PyError("NameError", "имя '" + name + "' не определено" + suggest(name, env));
    }

    private String suggest(String name, Env env) {
        List<String> candidates = new ArrayList<>(builtins.keySet());
        candidates.addAll(host.dynamicNames());
        for (Env e = env; e != null; e = e.parent) candidates.addAll(e.vars.keySet());
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String c : candidates) {
            if (c.equalsIgnoreCase(name)) return ". Может быть, " + c + "? (большие и маленькие буквы различаются)";
            int d = levenshtein(name.toLowerCase(), c.toLowerCase());
            if (d < bestD) { bestD = d; best = c; }
        }
        if (best != null && bestD <= Math.max(1, name.length() / 4)) return ". Может быть, " + best + "?";
        if (name.equals("true") || name.equals("false")) return ". Пишите True / False с большой буквы";
        return "";
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private void assign(Node t, Object v, Env env) {
        if (t instanceof Name n) {
            if (builtins.get(n.id) instanceof Builtin && Character.isUpperCase(n.id.charAt(0)) && env == globals && !globals.vars.containsKey(n.id)) {
                // allowed, but overriding a world function is a common mistake
                host.print("Внимание: переменная '" + n.id + "' заменила встроенную функцию с таким же именем", true);
            }
            setName(n.id, v, env);
        } else if (t instanceof Index ix) {
            Py.setItem(eval(ix.obj, env), evalIndex(ix.index, env), v);
        } else if (t instanceof TupleN || t instanceof ListN) {
            List<Node> targets = t instanceof TupleN tn ? tn.items : ((ListN) t).items;
            List<Object> vals = Py.toList(v);
            int star = -1;
            for (int i = 0; i < targets.size(); i++) if (targets.get(i) instanceof Starred) star = i;
            if (star < 0) {
                if (vals.size() != targets.size())
                    throw new PyError("ValueError", "нужно " + targets.size() + " значения для распаковки, а получено " + vals.size());
                for (int i = 0; i < targets.size(); i++) assign(targets.get(i), vals.get(i), env);
            } else {
                int after = targets.size() - star - 1;
                if (vals.size() < targets.size() - 1) throw new PyError("ValueError", "не хватает значений для распаковки");
                for (int i = 0; i < star; i++) assign(targets.get(i), vals.get(i), env);
                assign(((Starred) targets.get(star)).value, new PyList(vals.subList(star, vals.size() - after)), env);
                for (int i = 0; i < after; i++) assign(targets.get(star + 1 + i), vals.get(vals.size() - after + i), env);
            }
        } else if (t instanceof Attr) {
            throw new PyError("AttributeError", "присваивание атрибутам не поддерживается — используйте словарь: obj[\"имя\"] = ...");
        } else {
            throw new PyError("SyntaxError", "нельзя присвоить значение этому выражению");
        }
    }

    private void delete(Node t, Env env) {
        if (t instanceof Name n) {
            Env e = env;
            if (e.globals != null && e.globals.contains(n.id)) e = globals;
            if (e.vars.remove(n.id) == null && !e.vars.containsKey(n.id)) {
                // remove() returns null both when missing and when the value is None
            }
        } else if (t instanceof Index ix) {
            Py.delItem(eval(ix.obj, env), evalIndex(ix.index, env));
        } else if (t instanceof TupleN tn) {
            for (Node x : tn.items) delete(x, env);
        } else {
            throw new PyError("SyntaxError", "нельзя удалить это выражение");
        }
    }

    // ---------- functions ----------
    private PyFunction makeFunction(String name, Params ps, List<Node> body, Node exprBody, Set<String> locals, Env env) {
        List<Object> defaults = new ArrayList<>();
        for (Node d : ps.defaults) defaults.add(d == null ? NO_VALUE : eval(d, env));
        return new PyFunction(name, ps, defaults, body, exprBody, locals, env);
    }

    Object callFunction(PyFunction f, List<Object> args, Map<String, Object> kwargs, int line) {
        if (depth >= MAX_DEPTH) throw new PyError("RecursionError", "слишком глубокая рекурсия (функция " + f.name + " вызывает себя слишком много раз)");
        Env env = new Env(f.closure, f.locals);
        Params ps = f.params;
        int n = ps.names.size();
        if (args.size() > n && ps.varArgs == null)
            throw new PyError("TypeError", f.name + "() принимает " + n + " аргумент(ов), а передано " + args.size());
        for (int i = 0; i < Math.min(n, args.size()); i++) env.vars.put(ps.names.get(i), args.get(i));
        if (ps.varArgs != null) env.vars.put(ps.varArgs, new PyTuple(args.size() > n ? args.subList(n, args.size()) : List.of()));
        PyDict extra = ps.kwArgs != null ? new PyDict() : null;
        for (var e : kwargs.entrySet()) {
            if (ps.names.contains(e.getKey())) {
                if (env.vars.containsKey(e.getKey())) throw new PyError("TypeError", f.name + "(): аргумент '" + e.getKey() + "' передан дважды");
                env.vars.put(e.getKey(), e.getValue());
            } else if (extra != null) {
                extra.put(e.getKey(), e.getValue());
            } else {
                throw new PyError("TypeError", f.name + "() не знает аргумента '" + e.getKey() + "'");
            }
        }
        if (extra != null) env.vars.put(ps.kwArgs, extra);
        for (int i = 0; i < n; i++) {
            String pn = ps.names.get(i);
            if (!env.vars.containsKey(pn)) {
                Object d = f.defaults.get(i);
                if (d == NO_VALUE) throw new PyError("TypeError", f.name + "(): не хватает аргумента '" + pn + "'");
                env.vars.put(pn, d);
            }
        }
        depth++;
        try {
            if (f.exprBody != null) return eval(f.exprBody, env);
            execBlock(f.body, env);
            return null;
        } catch (ReturnEx r) {
            return r.value;
        } catch (PyError err) {
            if (err.line > 0 && line > 0 && err.trace.size() < 8) err.trace.add(f.name + "() из строки " + line);
            throw err;
        } catch (BreakEx | ContinueEx e) {
            throw new PyError("SyntaxError", "break/continue вне цикла");
        } finally {
            depth--;
        }
    }

    // ---------- expressions ----------
    private Object evalIndex(Node idx, Env env) {
        if (idx instanceof SliceN s) {
            return new Py.Slice(s.lo == null ? null : eval(s.lo, env), s.hi == null ? null : eval(s.hi, env), s.step == null ? null : eval(s.step, env));
        }
        return eval(idx, env);
    }

    public Object eval(Node e, Env env) {
        if (e instanceof Const c) return c.value;
        if (e instanceof Name n) return lookup(n.id, env);
        if (e instanceof BinOp b) return Py.binop(b.op, eval(b.left, env), eval(b.right, env));
        if (e instanceof Call c) return evalCall(c, env);
        if (e instanceof Compare c) {
            Object left = eval(c.operands.get(0), env);
            for (int i = 0; i < c.ops.size(); i++) {
                Object right = eval(c.operands.get(i + 1), env);
                if (!compare(c.ops.get(i), left, right)) return false;
                left = right;
            }
            return true;
        }
        if (e instanceof BoolOp b) {
            Object l = eval(b.left, env);
            if (b.and) return Py.truthy(l) ? eval(b.right, env) : l;
            return Py.truthy(l) ? l : eval(b.right, env);
        }
        if (e instanceof Unary u) {
            Object v = eval(u.operand, env);
            return switch (u.op) {
                case "not" -> !Py.truthy(v);
                case "-" -> {
                    if (Py.isInt(v)) yield -Py.toLong(v);
                    if (v instanceof Double d) yield -d;
                    throw new PyError("TypeError", "нельзя применить '-' к " + Py.typeName(v));
                }
                case "+" -> {
                    if (Py.isInt(v)) yield Py.toLong(v);
                    if (v instanceof Double) yield v;
                    throw new PyError("TypeError", "нельзя применить '+' к " + Py.typeName(v));
                }
                case "~" -> ~Py.toLong(v);
                default -> throw new PyError("SyntaxError", "неизвестный оператор " + u.op);
            };
        }
        if (e instanceof Attr a) return getAttr(eval(a.obj, env), a.name);
        if (e instanceof Index ix) return Py.getItem(eval(ix.obj, env), evalIndex(ix.index, env));
        if (e instanceof ListN l) {
            PyList out = new PyList();
            for (Node x : l.items) addItem(out, x, env);
            return out;
        }
        if (e instanceof TupleN t) {
            PyList out = new PyList();
            for (Node x : t.items) addItem(out, x, env);
            return new PyTuple(out);
        }
        if (e instanceof DictN d) {
            PyDict out = new PyDict();
            for (int i = 0; i < d.keys.size(); i++) out.put(eval(d.keys.get(i), env), eval(d.values.get(i), env));
            return out;
        }
        if (e instanceof SetN s) {
            PySet out = new PySet();
            for (Node x : s.items) out.add(eval(x, env));
            return out;
        }
        if (e instanceof IfExp i) return Py.truthy(eval(i.cond, env)) ? eval(i.then, env) : eval(i.orElse, env);
        if (e instanceof Lambda l) return makeFunction("<lambda>", l.params, null, l.body, Set.of(), env);
        if (e instanceof Comp c) return evalComp(c, env);
        if (e instanceof FStr f) {
            StringBuilder sb = new StringBuilder();
            for (Object part : f.parts) {
                if (part instanceof String s) {
                    sb.append(s);
                    continue;
                }
                FPart fp = (FPart) part;
                Object v = eval(fp.expr, env);
                if ("r".equals(fp.conv)) v = Py.repr(v);
                else if ("s".equals(fp.conv)) v = Py.str(v);
                String spec = fp.specExpr != null ? Py.str(eval(fp.specExpr, env)) : fp.spec;
                sb.append(Py.format(v, spec));
            }
            return sb.toString();
        }
        if (e instanceof SliceN) throw new PyError("SyntaxError", "срез вне квадратных скобок");
        if (e instanceof Starred) throw new PyError("SyntaxError", "звёздочку * здесь использовать нельзя");
        throw new PyError("SyntaxError", "неподдерживаемое выражение");
    }

    private void addItem(PyList out, Node x, Env env) {
        if (x instanceof Starred s) out.addAll(Py.toList(eval(s.value, env)));
        else out.add(eval(x, env));
    }

    private boolean compare(String op, Object a, Object b) {
        return switch (op) {
            case "==" -> Py.eq(a, b);
            case "!=" -> !Py.eq(a, b);
            case "<" -> Py.compare(a, b, op) < 0;
            case ">" -> Py.compare(a, b, op) > 0;
            case "<=" -> Py.compare(a, b, op) <= 0;
            case ">=" -> Py.compare(a, b, op) >= 0;
            case "in" -> Py.contains(b, a);
            case "not in" -> !Py.contains(b, a);
            case "is" -> a == b || (a != null && b != null && (a instanceof Boolean || a instanceof Long) && a.equals(b));
            case "is not" -> !(a == b || (a != null && b != null && (a instanceof Boolean || a instanceof Long) && a.equals(b)));
            default -> throw new PyError("SyntaxError", "неизвестное сравнение " + op);
        };
    }

    private Object evalComp(Comp c, Env env) {
        Env inner = new Env(env, null);
        PyList list = new PyList();
        PyDict dict = c.kind == 'd' ? new PyDict() : null;
        PySet set = c.kind == 's' ? new PySet() : null;
        compLoop(c, 0, inner, list, dict, set);
        if (dict != null) return dict;
        if (set != null) return set;
        return list;
    }

    private void compLoop(Comp c, int k, Env env, PyList list, PyDict dict, PySet set) {
        if (k == c.fors.size()) {
            if (dict != null) dict.put(eval(c.elt, env), eval(c.valueElt, env));
            else if (set != null) set.add(eval(c.elt, env));
            else {
                list.add(eval(c.elt, env));
                if (list.size() > Py.MAX_SIZE) throw new PyError("MemoryError", "список слишком большой");
            }
            return;
        }
        CompFor f = c.fors.get(k);
        for (Iterator<Object> it = Py.iter(eval(f.iter, env)); it.hasNext(); ) {
            host.checkAlive();
            assign(f.target, it.next(), env);
            boolean ok = true;
            for (Node cond : f.conds) if (!Py.truthy(eval(cond, env))) { ok = false; break; }
            if (ok) compLoop(c, k + 1, env, list, dict, set);
        }
    }

    private Object evalCall(Call c, Env env) {
        Object fn = eval(c.func, env);
        List<Object> args = new ArrayList<>(c.args.size());
        for (Node a : c.args) args.add(eval(a, env));
        if (c.starArgs != null) args.addAll(Py.toList(eval(c.starArgs, env)));
        Map<String, Object> kw = c.kwNames.isEmpty() && c.starKw == null ? Map.of() : new LinkedHashMap<>();
        for (int i = 0; i < c.kwNames.size(); i++) kw.put(c.kwNames.get(i), eval(c.kwValues.get(i), env));
        if (c.starKw != null) {
            Object d = eval(c.starKw, env);
            if (!(d instanceof PyDict pd)) throw new PyError("TypeError", "после ** нужен словарь");
            for (Object k : pd.keyList()) kw.put(Py.str(k), pd.get(k));
        }
        try {
            return callValue(fn, args, kw, c.line);
        } catch (PyError err) {
            throw err.at(c.line);
        }
    }

    public Object getAttr(Object o, String name) {
        if (o instanceof PyModule m) {
            if (m.attrs.containsKey(name)) return m.attrs.get(name);
            throw new PyError("AttributeError", "в модуле " + m.name + " нет '" + name + "'");
        }
        if (o instanceof PyException pe) {
            if (name.equals("args")) return pe.args;
            if (name.equals("message")) return pe.message;
        }
        if (o instanceof PyFunction f && name.equals("__name__")) return f.name;
        if (Methods.has(o, name)) return new BoundMethod(o, name);
        if (o instanceof PyDict d && d.has(name)) return d.get(name);
        throw new PyError("AttributeError", "у " + Py.typeName(o) + " нет атрибута '" + name + "'");
    }
}
