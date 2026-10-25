package com.miramwh.codingworld.py;

import java.util.List;
import java.util.Set;

/** Syntax tree for the supported Python subset. */
public abstract class Node {
    public final int line;

    Node(int line) {
        this.line = line;
    }

    // ---------- expressions ----------
    public static final class Const extends Node {
        final Object value;
        Const(int line, Object value) { super(line); this.value = value; }
    }

    public static final class Name extends Node {
        final String id;
        Name(int line, String id) { super(line); this.id = id; }
    }

    public static final class BinOp extends Node {
        final String op;
        final Node left, right;
        BinOp(int line, String op, Node left, Node right) { super(line); this.op = op; this.left = left; this.right = right; }
    }

    public static final class Unary extends Node {
        final String op;
        final Node operand;
        Unary(int line, String op, Node operand) { super(line); this.op = op; this.operand = operand; }
    }

    public static final class BoolOp extends Node {
        final boolean and;
        final Node left, right;
        BoolOp(int line, boolean and, Node left, Node right) { super(line); this.and = and; this.left = left; this.right = right; }
    }

    public static final class Compare extends Node {
        final List<String> ops;
        final List<Node> operands;
        Compare(int line, List<String> ops, List<Node> operands) { super(line); this.ops = ops; this.operands = operands; }
    }

    public static final class Call extends Node {
        final Node func;
        final List<Node> args;
        final List<String> kwNames;
        final List<Node> kwValues;
        final Node starArgs, starKw;
        Call(int line, Node func, List<Node> args, List<String> kwNames, List<Node> kwValues, Node starArgs, Node starKw) {
            super(line); this.func = func; this.args = args; this.kwNames = kwNames; this.kwValues = kwValues; this.starArgs = starArgs; this.starKw = starKw;
        }
    }

    public static final class Attr extends Node {
        final Node obj;
        final String name;
        Attr(int line, Node obj, String name) { super(line); this.obj = obj; this.name = name; }
    }

    public static final class Index extends Node {
        final Node obj, index;
        Index(int line, Node obj, Node index) { super(line); this.obj = obj; this.index = index; }
    }

    public static final class SliceN extends Node {
        final Node lo, hi, step;
        SliceN(int line, Node lo, Node hi, Node step) { super(line); this.lo = lo; this.hi = hi; this.step = step; }
    }

    public static final class ListN extends Node {
        final List<Node> items;
        ListN(int line, List<Node> items) { super(line); this.items = items; }
    }

    public static final class TupleN extends Node {
        final List<Node> items;
        TupleN(int line, List<Node> items) { super(line); this.items = items; }
    }

    public static final class DictN extends Node {
        final List<Node> keys, values;
        DictN(int line, List<Node> keys, List<Node> values) { super(line); this.keys = keys; this.values = values; }
    }

    public static final class SetN extends Node {
        final List<Node> items;
        SetN(int line, List<Node> items) { super(line); this.items = items; }
    }

    public static final class IfExp extends Node {
        final Node cond, then, orElse;
        IfExp(int line, Node cond, Node then, Node orElse) { super(line); this.cond = cond; this.then = then; this.orElse = orElse; }
    }

    public static final class Lambda extends Node {
        final Params params;
        final Node body;
        Lambda(int line, Params params, Node body) { super(line); this.params = params; this.body = body; }
    }

    /** One "for target in iter if cond..." clause of a comprehension. */
    public static final class CompFor {
        final Node target, iter;
        final List<Node> conds;
        CompFor(Node target, Node iter, List<Node> conds) { this.target = target; this.iter = iter; this.conds = conds; }
    }

    public static final class Comp extends Node {
        final char kind; // 'l' list, 'd' dict, 's' set, 'g' generator (treated as list)
        final Node elt, valueElt;
        final List<CompFor> fors;
        Comp(int line, char kind, Node elt, Node valueElt, List<CompFor> fors) { super(line); this.kind = kind; this.elt = elt; this.valueElt = valueElt; this.fors = fors; }
    }

    public static final class FStr extends Node {
        final List<Object> parts; // String literal or FPart
        FStr(int line, List<Object> parts) { super(line); this.parts = parts; }
    }

    public static final class FPart {
        final Node expr;
        final String conv, spec;
        final Node specExpr;
        FPart(Node expr, String conv, String spec, Node specExpr) { this.expr = expr; this.conv = conv; this.spec = spec; this.specExpr = specExpr; }
    }

    public static final class Starred extends Node {
        final Node value;
        Starred(int line, Node value) { super(line); this.value = value; }
    }

    // ---------- statements ----------
    public static final class Params {
        final List<String> names;
        final List<Node> defaults; // same size as names, null when no default
        final String varArgs, kwArgs;
        Params(List<String> names, List<Node> defaults, String varArgs, String kwArgs) { this.names = names; this.defaults = defaults; this.varArgs = varArgs; this.kwArgs = kwArgs; }
    }

    public static final class ExprStmt extends Node {
        final Node expr;
        ExprStmt(int line, Node expr) { super(line); this.expr = expr; }
    }

    public static final class Assign extends Node {
        final List<Node> targets;
        final Node value;
        Assign(int line, List<Node> targets, Node value) { super(line); this.targets = targets; this.value = value; }
    }

    public static final class AugAssign extends Node {
        final Node target;
        final String op;
        final Node value;
        AugAssign(int line, Node target, String op, Node value) { super(line); this.target = target; this.op = op; this.value = value; }
    }

    public static final class If extends Node {
        final Node cond;
        final List<Node> body, orElse;
        If(int line, Node cond, List<Node> body, List<Node> orElse) { super(line); this.cond = cond; this.body = body; this.orElse = orElse; }
    }

    public static final class While extends Node {
        final Node cond;
        final List<Node> body, orElse;
        While(int line, Node cond, List<Node> body, List<Node> orElse) { super(line); this.cond = cond; this.body = body; this.orElse = orElse; }
    }

    public static final class For extends Node {
        final Node target, iter;
        final List<Node> body, orElse;
        For(int line, Node target, Node iter, List<Node> body, List<Node> orElse) { super(line); this.target = target; this.iter = iter; this.body = body; this.orElse = orElse; }
    }

    public static final class Def extends Node {
        final String name;
        final Params params;
        final List<Node> body;
        final Set<String> locals;
        final List<Node> decorators;
        Def(int line, String name, Params params, List<Node> body, Set<String> locals, List<Node> decorators) {
            super(line); this.name = name; this.params = params; this.body = body; this.locals = locals; this.decorators = decorators;
        }
    }

    public static final class Return extends Node {
        final Node value;
        Return(int line, Node value) { super(line); this.value = value; }
    }

    public static final class Simple extends Node {
        final String kind; // break, continue, pass
        Simple(int line, String kind) { super(line); this.kind = kind; }
    }

    public static final class Global extends Node {
        final List<String> names;
        final boolean nonlocal;
        Global(int line, List<String> names, boolean nonlocal) { super(line); this.names = names; this.nonlocal = nonlocal; }
    }

    public static final class Handler {
        final List<String> types; // empty = catch everything
        final String asName;
        final List<Node> body;
        final int line;
        Handler(int line, List<String> types, String asName, List<Node> body) { this.line = line; this.types = types; this.asName = asName; this.body = body; }
    }

    public static final class Try extends Node {
        final List<Node> body;
        final List<Handler> handlers;
        final List<Node> orElse, finallyBody;
        Try(int line, List<Node> body, List<Handler> handlers, List<Node> orElse, List<Node> finallyBody) {
            super(line); this.body = body; this.handlers = handlers; this.orElse = orElse; this.finallyBody = finallyBody;
        }
    }

    public static final class Raise extends Node {
        final Node exc;
        Raise(int line, Node exc) { super(line); this.exc = exc; }
    }

    public static final class Del extends Node {
        final List<Node> targets;
        Del(int line, List<Node> targets) { super(line); this.targets = targets; }
    }

    public static final class Import extends Node {
        final String module, asName;
        final List<String> names, asNames; // for "from m import a, b"; null for plain import
        Import(int line, String module, String asName, List<String> names, List<String> asNames) {
            super(line); this.module = module; this.asName = asName; this.names = names; this.asNames = asNames;
        }
    }

    public static final class Assert extends Node {
        final Node cond, msg;
        Assert(int line, Node cond, Node msg) { super(line); this.cond = cond; this.msg = msg; }
    }
}
