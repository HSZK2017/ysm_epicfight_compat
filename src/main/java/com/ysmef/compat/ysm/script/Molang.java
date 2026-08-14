package com.ysmef.compat.ysm.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal molang interpreter for the YSM animation scripts evaluated by this mod.
 *
 * Supported: numbers, v./q./query./ysm./ctrl./math./temp./t. variables, math.* and
 * query.* function calls, string literals (only as call arguments, e.g.
 * ctrl.hold('mainhand', ':sword')), arithmetic (+ - * / %), comparisons, equality,
 * && || !, ternary ?:, null-coalescing ??, statement sequences with ';', and variable
 * assignment (v.x = ..., +=, -=). Semicolons evaluate all statements and yield the
 * last value; unassigned variables read as "unset" (0 in arithmetic, subject of ??).
 *
 * Expressions are compiled once and cached globally.
 *
 * Performance notes (hot path - evaluated per frame per animated player):
 * - query/variable paths are interned into integer ids at compile time; the
 *   Env implementations read id-keyed slots instead of doing String hashing
 * - function-call argument buffers are reused (no per-call double[] allocation)
 * - variable references are classified (var vs query) at compile time
 * - pure-numeric expressions are constant-folded to a single evaluation
 */
public final class Molang {

    public interface Env {
        double getVarById(int id);

        boolean hasVarById(int id);

        void setVarById(int id, double value);

        double getQueryById(int id);

        double callFunction(String name, double[] args);

        /** ctrl.hold('mainhand', ':sword') style calls with string arguments. */
        double callStringFunction(String name, String[] args);

        // String-based convenience entry points (interning on the fly).
        default double getVar(String path) {
            return getVarById(idOf(path));
        }

        default boolean hasVar(String path) {
            return hasVarById(idOf(path));
        }

        default void setVar(String path, double value) {
            setVarById(idOf(path), value);
        }

        default double getQuery(String path) {
            return getQueryById(queryIdOf(path));
        }
    }

    public interface Expr {
        double eval(Env env);
    }

    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();
    private static final Expr ZERO = env -> 0.0;

    // ------------------------------------------------------------------
    // Path interning: query/variable paths become stable integer ids, so the
    // hot evaluation path reads id-keyed slots instead of hashing Strings.
    // ------------------------------------------------------------------

    private static final Map<String, Integer> ID_BY_NAME = new ConcurrentHashMap<>();
    private static final List<String> NAME_BY_ID = new ArrayList<>();
    private static int nextId = 0;

    /** Intern a path and return its stable integer id. */
    public static int idOf(String name) {
        Integer existing = ID_BY_NAME.get(name);
        if (existing != null) {
            return existing;
        }
        synchronized (Molang.class) {
            Integer again = ID_BY_NAME.get(name);
            if (again != null) {
                return again;
            }
            int id = nextId++;
            ID_BY_NAME.put(name, id);
            NAME_BY_ID.add(name);
            return id;
        }
    }

    /** Resolve an id back to its path (cold path, e.g. default environments). */
    public static String nameOf(int id) {
        synchronized (Molang.class) {
            return id >= 0 && id < NAME_BY_ID.size() ? NAME_BY_ID.get(id) : "";
        }
    }

    /** Molang "q.foo" is an alias for "query.foo"; intern under the canonical form. */
    public static String normalizeQuery(String path) {
        return path.startsWith("q.") ? "query." + path.substring(2) : path;
    }

    public static int queryIdOf(String path) {
        return idOf(normalizeQuery(path));
    }

    // ------------------------------------------------------------------
    // Compilation
    // ------------------------------------------------------------------

    public static Expr compile(String src) {
        if (src == null || src.isEmpty()) {
            return ZERO;
        }
        return CACHE.computeIfAbsent(src, Molang::parse);
    }

    public static double eval(String src, Env env) {
        return compile(src).eval(env);
    }

    private static Expr parse(String src) {
        try {
            Parser parser = new Parser(src);
            Expr expr = parser.parseStatements();
            parser.expectEnd();
            if (isPureNumeric(src)) {
                // constant folding: evaluate once at compile time, the per-frame
                // cost of pure numbers/arithmetic drops to a single return
                try {
                    double value = expr.eval(FOLD_ENV);
                    return env -> value;
                } catch (Throwable t) {
                    return expr;
                }
            }
            return expr;
        } catch (RuntimeException e) {
            // The failure is cached (CACHE.computeIfAbsent) so this logs once per
            // source string; silent zeros made broken molang nearly impossible to
            // diagnose (variant visibility just stopped working).
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: failed to compile molang '{}': {}", src, e.toString());
            return ZERO;
        }
    }

    /** Whether the source contains no identifiers/strings, i.e. is pure numeric. */
    private static boolean isPureNumeric(String src) {
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$' || c == '\'') {
                return false;
            }
        }
        return true;
    }

    private static final Env FOLD_ENV = new Env() {
        @Override
        public double getVarById(int id) {
            throw new IllegalStateException("constant folding touched a variable");
        }

        @Override
        public boolean hasVarById(int id) {
            throw new IllegalStateException("constant folding touched a variable");
        }

        @Override
        public void setVarById(int id, double value) {
            throw new IllegalStateException("constant folding touched a variable");
        }

        @Override
        public double getQueryById(int id) {
            throw new IllegalStateException("constant folding touched a query");
        }

        @Override
        public double callFunction(String name, double[] args) {
            throw new IllegalStateException("constant folding touched a function call");
        }

        @Override
        public double callStringFunction(String name, String[] args) {
            throw new IllegalStateException("constant folding touched a function call");
        }
    };

    // ------------------------------------------------------------------
    // Lexer
    // ------------------------------------------------------------------

    private static final int T_NUM = 0, T_IDENT = 1, T_STR = 2, T_OP = 3, T_END = 4;

    private record Token(int type, String text, double num) {}

    private static final class Lexer {
        private final String s;
        private int pos;

        Lexer(String s) {
            this.s = s;
        }

        Token next() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            if (pos >= s.length()) {
                return new Token(T_END, "", 0);
            }
            char c = s.charAt(pos);
            if (Character.isDigit(c) || (c == '.' && pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1)))) {
                int start = pos;
                boolean dot = false;
                while (pos < s.length()) {
                    char ch = s.charAt(pos);
                    if (Character.isDigit(ch)) {
                        pos++;
                    } else if (ch == '.' && !dot) {
                        dot = true;
                        pos++;
                    } else {
                        break;
                    }
                }
                return new Token(T_NUM, s.substring(start, pos), Double.parseDouble(s.substring(start, pos)));
            }
            if (c == '\'') {
                int start = ++pos;
                while (pos < s.length() && s.charAt(pos) != '\'') {
                    pos++;
                }
                String str = s.substring(start, pos);
                if (pos < s.length()) {
                    pos++;
                }
                return new Token(T_STR, str, 0);
            }
            if (isIdentStart(c)) {
                int start = pos;
                while (pos < s.length() && isIdentPart(s.charAt(pos))) {
                    pos++;
                }
                return new Token(T_IDENT, s.substring(start, pos), 0);
            }
            String two = pos + 1 < s.length() ? s.substring(pos, pos + 2) : "";
            switch (two) {
                case "==", "!=", "<=", ">=", "&&", "||", "??", "+=", "-=" -> {
                    pos += 2;
                    return new Token(T_OP, two, 0);
                }
                default -> {
                }
            }
            if ("+-*/%(),?:!<>=;".indexOf(c) >= 0) {
                pos++;
                return new Token(T_OP, String.valueOf(c), 0);
            }
            // Unknown characters (array brackets, double quotes, bitwise
            // operators, ...) previously were silently skipped and the whole
            // expression then fell back to a silent zero via "trailing tokens".
            // Throw instead so the parse failure is logged (see parse()).
            throw new IllegalStateException("unsupported character '" + c + "' at " + pos);
        }

        private static boolean isIdentStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$';
        }

        private static boolean isIdentPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
        }
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    private static final class Parser {
        private final Lexer lexer;
        private Token current;

        Parser(String src) {
            this.lexer = new Lexer(src);
            this.current = lexer.next();
        }

        private Token advance() {
            Token t = current;
            current = lexer.next();
            return t;
        }

        private boolean isOp(String op) {
            return current.type() == T_OP && current.text().equals(op);
        }

        private void expectOp(String op) {
            if (!isOp(op)) {
                throw new IllegalStateException("expected " + op);
            }
            advance();
        }

        void expectEnd() {
            if (current.type() != T_END) {
                throw new IllegalStateException("trailing tokens");
            }
        }

        Expr parseStatements() {
            List<Expr> statements = new ArrayList<>();
            statements.add(parseStatement());
            while (isOp(";")) {
                advance();
                if (current.type() == T_END) {
                    break;
                }
                statements.add(parseStatement());
            }
            if (statements.size() == 1) {
                return statements.get(0);
            }
            List<Expr> list = statements;
            return env -> {
                double v = 0;
                for (Expr e : list) {
                    v = e.eval(env);
                }
                return v;
            };
        }

        private Expr parseStatement() {
            // assignment: v.path = expr | v.path += expr | v.path -= expr
            if (current.type() == T_IDENT && isVarNamespace(current.text())) {
                Token ident = current;
                int savedPos = lexer.pos;
                advance();
                if (isOp("=") || isOp("+=") || isOp("-=")) {
                    String op = advance().text();
                    Expr rhs = parseTernary();
                    int id = Molang.idOf(ident.text());
                    return env -> {
                        double v = rhs.eval(env);
                        if (op.equals("+=")) {
                            v = env.getVarById(id) + v;
                        } else if (op.equals("-=")) {
                            v = env.getVarById(id) - v;
                        }
                        env.setVarById(id, v);
                        return v;
                    };
                }
                // not an assignment: rewind
                lexer.pos = savedPos;
                current = ident;
            }
            return parseTernary();
        }

        private static boolean isVarNamespace(String ident) {
            int dot = ident.indexOf('.');
            if (dot <= 0) {
                return false;
            }
            String ns = ident.substring(0, dot);
            return ns.equals("v") || ns.equals("variable") || ns.equals("temp") || ns.equals("t");
        }

        private Expr parseTernary() {
            Expr cond = parseCoalesce();
            if (isOp("?")) {
                advance();
                Expr then = parseTernary();
                Expr otherwise;
                if (isOp(":")) {
                    advance();
                    otherwise = parseTernary();
                } else {
                    // Bedrock shorthand: `a ? b` without a `:` evaluates to b
                    // when a != 0 and to 0 otherwise. Used pervasively by YSM
                    // models (e.g. 'q.ground_speed<=2?4'); requiring the colon
                    // made every such expression silently evaluate to 0.
                    otherwise = env -> 0.0;
                }
                return env -> cond.eval(env) != 0.0 ? then.eval(env) : otherwise.eval(env);
            }
            return cond;
        }

        private Expr parseCoalesce() {
            Expr left = parseOr();
            if (isOp("??")) {
                advance();
                Expr right = parseCoalesce();
                return new CoalesceExpr(left, right);
            }
            return left;
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (isOp("||")) {
                advance();
                Expr right = parseAnd();
                Expr l = left;
                left = env -> (l.eval(env) != 0.0 || right.eval(env) != 0.0) ? 1.0 : 0.0;
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseEquality();
            while (isOp("&&")) {
                advance();
                Expr right = parseEquality();
                Expr l = left;
                left = env -> (l.eval(env) != 0.0 && right.eval(env) != 0.0) ? 1.0 : 0.0;
            }
            return left;
        }

        private Expr parseEquality() {
            Expr left = parseRelational();
            while (isOp("==") || isOp("!=")) {
                String op = advance().text();
                Expr right = parseRelational();
                Expr l = left;
                left = env -> {
                    double a = l.eval(env);
                    double b = right.eval(env);
                    boolean eq = Math.abs(a - b) < 1e-6;
                    return (op.equals("==") ? eq : !eq) ? 1.0 : 0.0;
                };
            }
            return left;
        }

        private Expr parseRelational() {
            Expr left = parseAdditive();
            while (isOp("<") || isOp(">") || isOp("<=") || isOp(">=")) {
                String op = advance().text();
                Expr right = parseAdditive();
                Expr l = left;
                left = env -> {
                    double a = l.eval(env);
                    double b = right.eval(env);
                    return switch (op) {
                        case "<" -> a < b ? 1.0 : 0.0;
                        case ">" -> a > b ? 1.0 : 0.0;
                        case "<=" -> a <= b ? 1.0 : 0.0;
                        default -> a >= b ? 1.0 : 0.0;
                    };
                };
            }
            return left;
        }

        private Expr parseAdditive() {
            Expr left = parseMultiplicative();
            while (isOp("+") || isOp("-")) {
                String op = advance().text();
                Expr right = parseMultiplicative();
                Expr l = left;
                left = env -> {
                    double a = l.eval(env);
                    double b = right.eval(env);
                    return sanitize(op.equals("+") ? a + b : a - b);
                };
            }
            return left;
        }

        private Expr parseMultiplicative() {
            Expr left = parseUnary();
            while (isOp("*") || isOp("/") || isOp("%")) {
                String op = advance().text();
                Expr right = parseUnary();
                Expr l = left;
                left = env -> {
                    double a = l.eval(env);
                    double b = right.eval(env);
                    double v = switch (op) {
                        case "*" -> a * b;
                        case "/" -> b == 0.0 ? 0.0 : a / b;
                        default -> b == 0.0 ? 0.0 : a % b;
                    };
                    return sanitize(v);
                };
            }
            return left;
        }

        private Expr parseUnary() {
            if (isOp("-")) {
                advance();
                Expr e = parseUnary();
                return env -> -e.eval(env);
            }
            if (isOp("!")) {
                advance();
                Expr e = parseUnary();
                return env -> e.eval(env) == 0.0 ? 1.0 : 0.0;
            }
            if (isOp("+")) {
                advance();
                return parseUnary();
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            if (current.type() == T_NUM) {
                double n = advance().num();
                return env -> n;
            }
            if (current.type() == T_STR) {
                // bare string literal (e.g. "ctrl.x != 'lit'"): no double can carry
                // a string, so it evaluates to a per-string marker in a range no
                // real molang value can reach. Comparisons then behave like YSM's
                // string semantics: number == string is false, number != string is
                // true (an unset ctrl.* query equals '' in the real YSM env, which
                // differs from any non-empty literal), and string == string
                // compares by marker identity. See {@link #stringMarker(String)}.
                String str = advance().text();
                double marker = stringMarker(str);
                return env -> marker;
            }
            if (isOp("(")) {
                advance();
                Expr e = parseStatements();
                expectOp(")");
                return e;
            }
            if (current.type() == T_IDENT) {
                String path = advance().text();
                if (isOp("(")) {
                    advance();
                    // arguments: numbers/expressions or string literals
                    List<Expr> args = new ArrayList<>();
                    List<String> stringArgs = new ArrayList<>();
                    boolean anyString = false;
                    if (!isOp(")")) {
                        do {
                            if (current.type() == T_STR) {
                                stringArgs.add(advance().text());
                                args.add(null);
                                anyString = true;
                            } else {
                                args.add(parseTernary());
                                stringArgs.add(null);
                            }
                        } while (consumeOp(","));
                    }
                    expectOp(")");
                    if (anyString) {
                        String[] sargs = stringArgs.toArray(new String[0]);
                        return env -> env.callStringFunction(path, sargs);
                    }
                    Expr[] exprArgs = args.toArray(new Expr[0]);
                    return env -> {
                        // reusable argument slots: no per-call allocation on the hot path
                        double[] values = ARG_SLOTS.get();
                        if (values.length < exprArgs.length) {
                            values = new double[exprArgs.length];
                            ARG_SLOTS.set(values);
                        }
                        for (int i = 0; i < exprArgs.length; i++) {
                            values[i] = exprArgs[i].eval(env);
                        }
                        return env.callFunction(path, values);
                    };
                }
                return new VarExpr(path);
            }
            throw new IllegalStateException("unexpected token: " + current.text());
        }

        private boolean consumeOp(String op) {
            if (isOp(op)) {
                advance();
                return true;
            }
            return false;
        }
    }

    private static double sanitize(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v;
    }

    /**
     * Marker value of a string literal used as a bare operand. The interned id
     * (0..n-1) is mapped into the -1e18 range, far below any real molang value
     * (health/positions/rotation/scale magnitudes), so:
     * - number == string -> false, number != string -> true
     * - string == same string -> true, string == different string -> false
     *
     * Ids are spaced 4096 apart because a double at 1e18 has an ULP of 128
     * (2^(59-52)); a spacing smaller than that would collapse distinct strings
     * into the same marker.
     */
    private static double stringMarker(String value) {
        return -1.0e18 - idOf(value) * 4096.0;
    }

    /** Per-thread scratch for function arguments (the eval threads are stable). */
    private static final ThreadLocal<double[]> ARG_SLOTS = ThreadLocal.withInitial(() -> new double[4]);

    /**
     * Variable reference: v./variable./temp./t. paths read and write vars; everything
     * else is a query. The path is interned and classified at compile time, so the
     * per-frame evaluation does no String work at all.
     */
    private static final class VarExpr implements Expr {
        private final int id;
        private final boolean isVar;

        VarExpr(String path) {
            this.isVar = Parser.isVarNamespace(path);
            this.id = isVar ? Molang.idOf(path) : Molang.queryIdOf(path);
        }

        @Override
        public double eval(Env env) {
            return isVar ? env.getVarById(id) : env.getQueryById(id);
        }
    }

    /** a ?? b: evaluates to a when a is "set", otherwise b. Only v.* lookups can be unset. */
    private static final class CoalesceExpr implements Expr {
        private final Expr left;
        private final Expr right;

        CoalesceExpr(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public double eval(Env env) {
            if (left instanceof VarExpr varExpr && varExpr.isVar) {
                if (env.hasVarById(varExpr.id)) {
                    return env.getVarById(varExpr.id);
                }
                return right.eval(env);
            }
            return left.eval(env);
        }
    }
}
