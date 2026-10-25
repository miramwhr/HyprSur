package com.miramwh.codingworld.py;

import com.miramwh.codingworld.py.Lexer.T;
import com.miramwh.codingworld.py.Lexer.Token;
import com.miramwh.codingworld.py.Node.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Recursive descent parser for the supported Python subset. */
public final class Parser {
    private static final Set<String> KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
            "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield");
    private static final Set<String> AUG = Set.of("+=", "-=", "*=", "/=", "//=", "%=", "**=", "&=", "|=", "^=", "<<=", ">>=");

    private final List<Token> toks;
    private int p = 0;
    /** Names assigned in each enclosing function, used for local-variable detection. */
    private final Deque<Set<String>> assigned = new ArrayDeque<>();
    private final Deque<Set<String>> declaredGlobal = new ArrayDeque<>();

    public Parser(List<Token> toks) {
        this.toks = toks;
    }

    public static List<Node> parse(String src) {
        return new Parser(new Lexer(src).tokenize()).file();
    }

    // ---------- helpers ----------
    private Token peek() { return toks.get(p); }
    private Token peek(int k) { return toks.get(Math.min(p + k, toks.size() - 1)); }
    private Token next() { return toks.get(p++); }
    private boolean at(String s) { Token t = peek(); return (t.type == T.OP || t.type == T.NAME) && t.text.equals(s); }
    private boolean atType(T type) { return peek().type == type; }

    private boolean accept(String s) {
        if (at(s)) { p++; return true; }
        return false;
    }

    private Token expect(String s) {
        if (!at(s)) {
            Token t = peek();
            String hint = "";
            if (s.equals(":") ) hint = " (после if/for/while/def нужно двоеточие)";
            else if (s.equals(")")) hint = " (не закрыта скобка?)";
            throw new PyError("SyntaxError", "ожидалось '" + s + "', а найдено " + t + hint, t.line);
        }
        return next();
    }

    private String name() {
        Token t = peek();
        if (t.type != T.NAME || KEYWORDS.contains(t.text))
            throw new PyError("SyntaxError", "ожидалось имя, а найдено " + t, t.line);
        p++;
        return t.text;
    }

    private PyError err(String msg) {
        return new PyError("SyntaxError", msg, peek().line);
    }

    private void markAssigned(String n) {
        if (!assigned.isEmpty()) assigned.peek().add(n);
    }

    private void markTarget(Node t) {
        if (t instanceof Name nm) markAssigned(nm.id);
        else if (t instanceof TupleN tn) tn.items.forEach(this::markTarget);
        else if (t instanceof ListN ln) ln.items.forEach(this::markTarget);
        else if (t instanceof Starred s) markTarget(s.value);
    }

    private void checkTarget(Node t) {
        if (t instanceof Name || t instanceof Index || t instanceof Attr) return;
        if (t instanceof TupleN tn) { tn.items.forEach(this::checkTarget); return; }
        if (t instanceof ListN ln) { ln.items.forEach(this::checkTarget); return; }
        if (t instanceof Starred s) { checkTarget(s.value); return; }
        if (t instanceof Call) throw new PyError("SyntaxError", "нельзя присвоить значение вызову функции (может, нужно == ?)", t.line);
        if (t instanceof Const) throw new PyError("SyntaxError", "нельзя присвоить значение константе", t.line);
        throw new PyError("SyntaxError", "нельзя присвоить значение этому выражению (может, нужно == ?)", t.line);
    }

    // ---------- statements ----------
    public List<Node> file() {
        List<Node> body = new ArrayList<>();
        while (!atType(T.EOF)) {
            if (atType(T.NEWLINE)) { p++; continue; }
            if (atType(T.INDENT)) throw err("лишний отступ в начале строки");
            statement(body);
        }
        return body;
    }

    private void statement(List<Node> out) {
        Token t = peek();
        if (t.type == T.NAME) {
            switch (t.text) {
                case "if" -> { out.add(ifStmt()); return; }
                case "while" -> { out.add(whileStmt()); return; }
                case "for" -> { out.add(forStmt()); return; }
                case "def" -> { out.add(defStmt(new ArrayList<>())); return; }
                case "try" -> { out.add(tryStmt()); return; }
                case "class" -> throw new PyError("SyntaxError", "классы (class) пока не поддерживаются — используйте функции и словари", t.line);
                case "with" -> throw new PyError("SyntaxError", "'with' не поддерживается", t.line);
                case "elif", "else" -> throw new PyError("SyntaxError", "'" + t.text + "' без 'if' (или неправильный отступ)", t.line);
                case "except", "finally" -> throw new PyError("SyntaxError", "'" + t.text + "' без 'try'", t.line);
                default -> { }
            }
        }
        if (t.is("@")) {
            List<Node> decorators = new ArrayList<>();
            while (accept("@")) {
                decorators.add(test());
                if (!atType(T.NEWLINE)) throw err("после декоратора ожидается новая строка");
                p++;
            }
            if (!at("def")) throw err("после декоратора ожидается def");
            out.add(defStmt(decorators));
            return;
        }
        simpleStatements(out);
    }

    private void simpleStatements(List<Node> out) {
        out.add(smallStmt());
        while (accept(";")) {
            if (atType(T.NEWLINE)) break;
            out.add(smallStmt());
        }
        if (atType(T.EOF)) return;
        if (!atType(T.NEWLINE)) {
            Token t = peek();
            throw new PyError("SyntaxError", "неожиданное " + t + " (может, пропущена запятая, скобка или оператор?)", t.line);
        }
        p++;
    }

    private List<Node> block() {
        expect(":");
        List<Node> body = new ArrayList<>();
        if (!atType(T.NEWLINE)) {
            simpleStatements(body);
            return body;
        }
        p++;
        while (atType(T.NEWLINE)) p++;
        if (!atType(T.INDENT)) throw new PyError("IndentationError", "ожидается блок с отступом", peek().line);
        p++;
        while (!atType(T.DEDENT) && !atType(T.EOF)) {
            if (atType(T.NEWLINE)) { p++; continue; }
            if (atType(T.INDENT)) throw new PyError("IndentationError", "неожиданный отступ", peek().line);
            statement(body);
        }
        if (atType(T.DEDENT)) p++;
        return body;
    }

    private Node ifStmt() {
        int line = next().line;
        Node cond = namedTest();
        List<Node> body = block();
        List<Node> orElse = null;
        if (at("elif")) {
            orElse = new ArrayList<>();
            orElse.add(ifStmt());
        } else if (accept("else")) {
            orElse = block();
        }
        return new If(line, cond, body, orElse);
    }

    private Node whileStmt() {
        int line = next().line;
        Node cond = namedTest();
        List<Node> body = block();
        List<Node> orElse = accept("else") ? block() : null;
        return new While(line, cond, body, orElse);
    }

    private Node forStmt() {
        int line = next().line;
        Node target = targetList();
        markTarget(target);
        expect("in");
        Node iter = testList();
        List<Node> body = block();
        List<Node> orElse = accept("else") ? block() : null;
        return new For(line, target, iter, body, orElse);
    }

    private Node targetList() {
        int line = peek().line;
        List<Node> items = new ArrayList<>();
        items.add(starOrExpr());
        boolean tuple = false;
        while (at(",")) {
            p++;
            tuple = true;
            if (at("in") || at("=")) break;
            items.add(starOrExpr());
        }
        Node t = tuple ? new TupleN(line, items) : items.get(0);
        checkTarget(t);
        return t;
    }

    private Node starOrExpr() {
        if (at("*")) {
            int line = next().line;
            return new Starred(line, bitOr());
        }
        return bitOr();
    }

    private Node defStmt(List<Node> decorators) {
        int line = next().line;
        String fname = name();
        markAssigned(fname);
        expect("(");
        assigned.push(new LinkedHashSet<>());
        declaredGlobal.push(new HashSet<>());
        Params params = params(")");
        expect(")");
        if (accept("->")) test();
        params.names.forEach(this::markAssigned);
        if (params.varArgs != null) markAssigned(params.varArgs);
        if (params.kwArgs != null) markAssigned(params.kwArgs);
        List<Node> body = block();
        Set<String> locals = assigned.pop();
        locals.removeAll(declaredGlobal.pop());
        return new Def(line, fname, params, body, locals, decorators);
    }

    private Params params(String end) {
        List<String> names = new ArrayList<>();
        List<Node> defaults = new ArrayList<>();
        String varArgs = null, kwArgs = null;
        while (!at(end)) {
            if (accept("**")) {
                kwArgs = name();
            } else if (accept("*")) {
                if (!at(",") && !at(end)) varArgs = name();
            } else {
                String n = name();
                if (end.equals(")") && accept(":")) test();
                names.add(n);
                defaults.add(accept("=") ? test() : null);
            }
            if (!accept(",")) break;
        }
        return new Params(names, defaults, varArgs, kwArgs);
    }

    private Node tryStmt() {
        int line = next().line;
        List<Node> body = block();
        List<Handler> handlers = new ArrayList<>();
        while (at("except")) {
            int hl = next().line;
            List<String> types = new ArrayList<>();
            String asName = null;
            if (!at(":")) {
                if (accept("(")) {
                    do { types.add(dotted()); } while (accept(",") && !at(")"));
                    expect(")");
                } else {
                    types.add(dotted());
                }
                if (accept("as")) {
                    asName = name();
                    markAssigned(asName);
                }
            }
            handlers.add(new Handler(hl, types, asName, block()));
        }
        List<Node> orElse = null, fin = null;
        if (!handlers.isEmpty() && accept("else")) orElse = block();
        if (accept("finally")) fin = block();
        if (handlers.isEmpty() && fin == null) throw err("после try нужен except или finally");
        return new Try(line, body, handlers, orElse, fin);
    }

    private String dotted() {
        StringBuilder sb = new StringBuilder(name());
        while (accept(".")) sb.append('.').append(name());
        return sb.toString();
    }

    private Node smallStmt() {
        Token t = peek();
        int line = t.line;
        if (t.type == T.NAME) {
            switch (t.text) {
                case "pass", "break", "continue" -> { p++; return new Simple(line, t.text); }
                case "return" -> {
                    p++;
                    Node v = (atType(T.NEWLINE) || at(";") || atType(T.EOF)) ? null : testList();
                    return new Return(line, v);
                }
                case "global", "nonlocal" -> {
                    p++;
                    List<String> names = new ArrayList<>();
                    do { names.add(name()); } while (accept(","));
                    if (!declaredGlobal.isEmpty()) declaredGlobal.peek().addAll(names);
                    return new Global(line, names, t.text.equals("nonlocal"));
                }
                case "del" -> {
                    p++;
                    List<Node> targets = new ArrayList<>();
                    do { targets.add(bitOr()); } while (accept(","));
                    return new Del(line, targets);
                }
                case "raise" -> {
                    p++;
                    Node e = (atType(T.NEWLINE) || atType(T.EOF)) ? null : test();
                    if (accept("from")) test();
                    return new Raise(line, e);
                }
                case "assert" -> {
                    p++;
                    Node c = test();
                    Node m = accept(",") ? test() : null;
                    return new Assert(line, c, m);
                }
                case "import" -> {
                    p++;
                    String mod = dotted();
                    String as = accept("as") ? name() : null;
                    markAssigned(as != null ? as : mod);
                    if (accept(",")) throw err("импортируйте модули по одному в строке");
                    return new Import(line, mod, as, null, null);
                }
                case "from" -> {
                    p++;
                    String mod = dotted();
                    expect("import");
                    List<String> names = new ArrayList<>(), asNames = new ArrayList<>();
                    boolean paren = accept("(");
                    if (accept("*")) {
                        names.add("*");
                        asNames.add("*");
                    } else {
                        do {
                            if (paren && at(")")) break;
                            String n = name();
                            String as = accept("as") ? name() : n;
                            names.add(n);
                            asNames.add(as);
                            markAssigned(as);
                        } while (accept(","));
                    }
                    if (paren) expect(")");
                    return new Import(line, mod, null, names, asNames);
                }
                default -> { }
            }
        }
        Node first = testListStar();
        if (peek().type == T.OP && AUG.contains(peek().text)) {
            String op = next().text;
            checkTarget(first);
            if (first instanceof TupleN) throw new PyError("SyntaxError", "нельзя использовать " + op + " с несколькими переменными", line);
            markTarget(first);
            Node value = testList();
            return new AugAssign(line, first, op.substring(0, op.length() - 1), value);
        }
        if (at("=")) {
            List<Node> targets = new ArrayList<>();
            targets.add(first);
            Node value = null;
            while (accept("=")) {
                value = testListStar();
                if (at("=")) targets.add(value);
            }
            for (Node tg : targets) {
                checkTarget(tg);
                markTarget(tg);
            }
            return new Assign(line, targets, value);
        }
        if (at(":") && first instanceof Name) {
            // annotated assignment: x: int = 5
            p++;
            test();
            if (accept("=")) {
                Node value = testList();
                markTarget(first);
                return new Assign(line, List.of(first), value);
            }
            return new Simple(line, "pass");
        }
        return new ExprStmt(line, first);
    }

    // ---------- expressions ----------
    private Node testListStar() {
        int line = peek().line;
        Node first = at("*") ? starOrExpr() : test();
        if (!at(",")) return first;
        List<Node> items = new ArrayList<>();
        items.add(first);
        while (accept(",")) {
            if (endOfExprList()) break;
            items.add(at("*") ? starOrExpr() : test());
        }
        return new TupleN(line, items);
    }

    private boolean endOfExprList() {
        Token t = peek();
        return t.type == T.NEWLINE || t.type == T.EOF || t.is("=") || t.is(")") || t.is("]") || t.is("}")
                || t.is(":") || t.is(";") || (t.type == T.OP && AUG.contains(t.text));
    }

    private Node testList() {
        int line = peek().line;
        Node first = test();
        if (!at(",")) return first;
        List<Node> items = new ArrayList<>();
        items.add(first);
        while (accept(",")) {
            if (endOfExprList()) break;
            items.add(test());
        }
        return new TupleN(line, items);
    }

    private Node namedTest() {
        Node t = test();
        if (at("=") ) throw new PyError("SyntaxError", "в условии используйте == для сравнения, а не =", peek().line);
        return t;
    }

    private Node test() {
        if (at("lambda")) {
            int line = next().line;
            Params ps = params(":");
            expect(":");
            return new Lambda(line, ps, test());
        }
        Node e = orTest();
        if (at("if") ) {
            int line = next().line;
            Node cond = orTest();
            expect("else");
            Node orElse = test();
            return new IfExp(line, cond, e, orElse);
        }
        return e;
    }

    private Node orTest() {
        Node l = andTest();
        while (at("or")) {
            int line = next().line;
            l = new BoolOp(line, false, l, andTest());
        }
        return l;
    }

    private Node andTest() {
        Node l = notTest();
        while (at("and")) {
            int line = next().line;
            l = new BoolOp(line, true, l, notTest());
        }
        return l;
    }

    private Node notTest() {
        if (at("not")) {
            int line = next().line;
            return new Unary(line, "not", notTest());
        }
        return comparison();
    }

    private Node comparison() {
        int line = peek().line;
        Node first = bitOr();
        List<String> ops = null;
        List<Node> operands = null;
        while (true) {
            String op = null;
            Token t = peek();
            if (t.type == T.OP && (t.text.equals("==") || t.text.equals("!=") || t.text.equals("<") || t.text.equals(">")
                    || t.text.equals("<=") || t.text.equals(">="))) {
                op = t.text;
                p++;
            } else if (t.is("in")) {
                op = "in";
                p++;
            } else if (t.is("not") && peek(1).is("in")) {
                op = "not in";
                p += 2;
            } else if (t.is("is")) {
                p++;
                op = accept("not") ? "is not" : "is";
            }
            if (op == null) break;
            if (ops == null) {
                ops = new ArrayList<>();
                operands = new ArrayList<>();
                operands.add(first);
            }
            ops.add(op);
            operands.add(bitOr());
        }
        return ops == null ? first : new Compare(line, ops, operands);
    }

    private Node bitOr() {
        Node l = bitXor();
        while (at("|")) { int line = next().line; l = new BinOp(line, "|", l, bitXor()); }
        return l;
    }

    private Node bitXor() {
        Node l = bitAnd();
        while (at("^")) { int line = next().line; l = new BinOp(line, "^", l, bitAnd()); }
        return l;
    }

    private Node bitAnd() {
        Node l = shift();
        while (at("&")) { int line = next().line; l = new BinOp(line, "&", l, shift()); }
        return l;
    }

    private Node shift() {
        Node l = arith();
        while (at("<<") || at(">>")) { Token t = next(); l = new BinOp(t.line, t.text, l, arith()); }
        return l;
    }

    private Node arith() {
        Node l = term();
        while (at("+") || at("-")) { Token t = next(); l = new BinOp(t.line, t.text, l, term()); }
        return l;
    }

    private Node term() {
        Node l = factor();
        while (at("*") || at("/") || at("//") || at("%")) { Token t = next(); l = new BinOp(t.line, t.text, l, factor()); }
        return l;
    }

    private Node factor() {
        if (at("-") || at("+") || at("~")) {
            Token t = next();
            Node operand = factor();
            if (t.text.equals("-") && operand instanceof Const c) {
                if (c.value instanceof Long l) return new Const(t.line, -l);
                if (c.value instanceof Double d) return new Const(t.line, -d);
            }
            return new Unary(t.line, t.text, operand);
        }
        return power();
    }

    private Node power() {
        Node base = atomExpr();
        if (at("**")) {
            int line = next().line;
            return new BinOp(line, "**", base, factor());
        }
        return base;
    }

    private Node atomExpr() {
        Node e = atom();
        while (true) {
            if (at("(")) {
                e = call(e);
            } else if (at("[")) {
                int line = next().line;
                Node idx = subscriptList();
                expect("]");
                e = new Index(line, e, idx);
            } else if (at(".")) {
                int line = next().line;
                Token t = peek();
                if (t.type != T.NAME) throw new PyError("SyntaxError", "после точки ожидается имя", t.line);
                p++;
                e = new Attr(line, e, t.text);
            } else {
                return e;
            }
        }
    }

    private Node call(Node func) {
        int line = next().line;
        List<Node> args = new ArrayList<>();
        List<String> kwNames = new ArrayList<>();
        List<Node> kwValues = new ArrayList<>();
        Node starArgs = null, starKw = null;
        while (!at(")")) {
            if (accept("**")) {
                starKw = test();
            } else if (accept("*")) {
                starArgs = test();
            } else if (peek().type == T.NAME && peek(1).is("=") && !KEYWORDS.contains(peek().text)) {
                String n = next().text;
                p++;
                kwNames.add(n);
                kwValues.add(test());
            } else {
                if (!kwNames.isEmpty()) throw err("обычный аргумент не может идти после именованного");
                Node a = test();
                if (at("for")) {
                    a = comprehension('g', a, null, line);
                }
                args.add(a);
            }
            if (!accept(",")) break;
        }
        if (!at(")")) {
            Token t = peek();
            throw new PyError("SyntaxError", "ожидалась ',' или ')' в вызове функции, а найдено " + t, t.line);
        }
        p++;
        return new Call(line, func, args, kwNames, kwValues, starArgs, starKw);
    }

    private Node subscriptList() {
        int line = peek().line;
        Node first = subscript();
        if (!at(",")) return first;
        List<Node> items = new ArrayList<>();
        items.add(first);
        while (accept(",")) {
            if (at("]")) break;
            items.add(subscript());
        }
        return new TupleN(line, items);
    }

    private Node subscript() {
        int line = peek().line;
        Node lo = null, hi = null, step = null;
        if (!at(":")) {
            lo = test();
            if (!at(":")) return lo;
        }
        expect(":");
        if (!at("]") && !at(":") && !at(",")) hi = test();
        if (accept(":")) {
            if (!at("]") && !at(",")) step = test();
        }
        return new SliceN(line, lo, hi, step);
    }

    private Node comprehension(char kind, Node elt, Node valueElt, int line) {
        List<CompFor> fors = new ArrayList<>();
        while (at("for")) {
            p++;
            Node target = targetList();
            expect("in");
            Node iter = orTest();
            List<Node> conds = new ArrayList<>();
            while (at("if")) {
                p++;
                conds.add(orTestNoCond());
            }
            fors.add(new CompFor(target, iter, conds));
        }
        return new Comp(line, kind, elt, valueElt, fors);
    }

    private Node orTestNoCond() {
        return orTest();
    }

    private Node atom() {
        Token t = peek();
        int line = t.line;
        switch (t.type) {
            case NUMBER -> { p++; return new Const(line, t.value); }
            case STRING, FSTRING -> {
                List<Object> parts = new ArrayList<>();
                boolean anyF = false;
                while (atType(T.STRING) || atType(T.FSTRING)) {
                    Token s = next();
                    if (s.type == T.FSTRING) {
                        anyF = true;
                        parts.addAll(fstring(s.text, s.line));
                    } else {
                        parts.add(s.text);
                    }
                }
                if (!anyF) {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : parts) sb.append(o);
                    return new Const(line, sb.toString());
                }
                return new FStr(line, parts);
            }
            case NAME -> {
                switch (t.text) {
                    case "True" -> { p++; return new Const(line, Boolean.TRUE); }
                    case "False" -> { p++; return new Const(line, Boolean.FALSE); }
                    case "None" -> { p++; return new Const(line, null); }
                    default -> { }
                }
                if (KEYWORDS.contains(t.text)) throw new PyError("SyntaxError", "неожиданное слово '" + t.text + "'", line);
                p++;
                return new Name(line, t.text);
            }
            case OP -> {
                switch (t.text) {
                    case "(" -> {
                        p++;
                        if (accept(")")) return new TupleN(line, new ArrayList<>());
                        Node first = at("*") ? starOrExpr() : test();
                        if (at("for")) {
                            Node c = comprehension('g', first, null, line);
                            expect(")");
                            return c;
                        }
                        if (accept(")")) return first;
                        List<Node> items = new ArrayList<>();
                        items.add(first);
                        while (accept(",")) {
                            if (at(")")) break;
                            items.add(at("*") ? starOrExpr() : test());
                        }
                        expect(")");
                        return new TupleN(line, items);
                    }
                    case "[" -> {
                        p++;
                        List<Node> items = new ArrayList<>();
                        if (accept("]")) return new ListN(line, items);
                        Node first = at("*") ? starOrExpr() : test();
                        if (at("for")) {
                            Node c = comprehension('l', first, null, line);
                            expect("]");
                            return c;
                        }
                        items.add(first);
                        while (accept(",")) {
                            if (at("]")) break;
                            items.add(at("*") ? starOrExpr() : test());
                        }
                        expect("]");
                        return new ListN(line, items);
                    }
                    case "{" -> {
                        p++;
                        List<Node> keys = new ArrayList<>(), values = new ArrayList<>();
                        if (accept("}")) return new DictN(line, keys, values);
                        Node k = test();
                        if (accept(":")) {
                            Node v = test();
                            if (at("for")) {
                                Node c = comprehension('d', k, v, line);
                                expect("}");
                                return c;
                            }
                            keys.add(k);
                            values.add(v);
                            while (accept(",")) {
                                if (at("}")) break;
                                keys.add(test());
                                expect(":");
                                values.add(test());
                            }
                            expect("}");
                            return new DictN(line, keys, values);
                        }
                        if (at("for")) {
                            Node c = comprehension('s', k, null, line);
                            expect("}");
                            return c;
                        }
                        List<Node> items = new ArrayList<>();
                        items.add(k);
                        while (accept(",")) {
                            if (at("}")) break;
                            items.add(test());
                        }
                        expect("}");
                        return new SetN(line, items);
                    }
                    case "..." -> { p++; return new Const(line, null); }
                    default -> { }
                }
            }
            default -> { }
        }
        if (t.type == T.NEWLINE || t.type == T.EOF)
            throw new PyError("SyntaxError", "выражение не закончено", line);
        if (t.type == T.INDENT) throw new PyError("IndentationError", "неожиданный отступ", line);
        throw new PyError("SyntaxError", "неожиданное " + t, line);
    }

    /** Splits an f-string body into literal strings and expression parts. */
    private List<Object> fstring(String s, int line) {
        List<Object> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '{') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '{') { lit.append('{'); i += 2; continue; }
                if (lit.length() > 0) { parts.add(lit.toString()); lit.setLength(0); }
                int depth = 1, j = i + 1;
                char inStr = 0;
                while (j < s.length() && depth > 0) {
                    char d = s.charAt(j);
                    if (inStr != 0) { if (d == inStr) inStr = 0; }
                    else if (d == '\'' || d == '"') inStr = d;
                    else if (d == '{' || d == '[' || d == '(') depth++;
                    else if (d == '}' || d == ']' || d == ')') depth--;
                    if (depth > 0) j++;
                }
                if (depth != 0) throw new PyError("SyntaxError", "в f-строке не закрыта фигурная скобка", line);
                String inner = s.substring(i + 1, j);
                // split off !conv and :spec at top level
                String exprSrc = inner, conv = null, spec = null;
                int d2 = 0;
                char q = 0;
                for (int k = 0; k < inner.length(); k++) {
                    char ch = inner.charAt(k);
                    if (q != 0) { if (ch == q) q = 0; continue; }
                    if (ch == '\'' || ch == '"') { q = ch; continue; }
                    if (ch == '(' || ch == '[' || ch == '{') d2++;
                    else if (ch == ')' || ch == ']' || ch == '}') d2--;
                    else if (d2 == 0 && ch == '!' && k + 1 < inner.length() && inner.charAt(k + 1) != '=') {
                        exprSrc = inner.substring(0, k);
                        int colon = inner.indexOf(':', k);
                        conv = colon < 0 ? inner.substring(k + 1) : inner.substring(k + 1, colon);
                        if (colon >= 0) spec = inner.substring(colon + 1);
                        break;
                    } else if (d2 == 0 && ch == ':') {
                        exprSrc = inner.substring(0, k);
                        spec = inner.substring(k + 1);
                        break;
                    }
                }
                boolean selfDoc = exprSrc.trim().endsWith("=") && !exprSrc.trim().endsWith("==");
                if (selfDoc) {
                    parts.add(exprSrc);
                    exprSrc = exprSrc.trim();
                    exprSrc = exprSrc.substring(0, exprSrc.length() - 1);
                    if (conv == null && spec == null) conv = "r";
                }
                if (exprSrc.isBlank()) throw new PyError("SyntaxError", "пустое выражение {} в f-строке", line);
                Node expr = subExpr(exprSrc, line);
                Node specExpr = null;
                if (spec != null && spec.contains("{")) {
                    specExpr = new FStr(line, fstring(spec, line));
                    spec = null;
                }
                parts.add(new FPart(expr, conv, spec, specExpr));
                i = j + 1;
            } else if (c == '}') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '}') { lit.append('}'); i += 2; continue; }
                throw new PyError("SyntaxError", "одиночная '}' в f-строке (пишите }})", line);
            } else {
                lit.append(c);
                i++;
            }
        }
        if (lit.length() > 0) parts.add(lit.toString());
        return parts;
    }

    private Node subExpr(String src, int line) {
        List<Token> sub = new Lexer("(" + src + ")", line).tokenize();
        Parser ps = new Parser(sub);
        Node e = ps.testList();
        return e;
    }
}
