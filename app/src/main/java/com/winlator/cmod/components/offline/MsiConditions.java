package com.winlator.cmod.components.offline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows Installer's condition language, enough of it for what packages ask at install time
 * (a port of droiddeck-msi-install's Conditions). A condition that cannot be read gives the
 * caller's default.
 */
final class MsiConditions {
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(?:(\"[^\"]*\")|(-?\\d+)(?![\\w.])|(~?(?:<>|<=|>=|><|<<|>>|=|<|>))|([()])|([%$?&!]?[A-Za-z_][\\w.]*))");
    private static final String[] LEVELS = {"imp", "eqv", "xor", "or", "and"};

    private final Map<String, String> props;
    private final Function<String, Integer> featureState, componentState;
    private List<String[]> tokens;
    private int pos;

    MsiConditions(Map<String, String> props, Function<String, Integer> featureState, Function<String, Integer> componentState) {
        this.props = props; this.featureState = featureState; this.componentState = componentState;
    }

    boolean evaluate(String text, boolean def) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return true;
        try {
            tokens = tokenize(text);
            pos = 0;
            Object value = binary(0);
            if (pos != tokens.size()) return def;
            return truth(value);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return def;
        }
    }

    private static List<String[]> tokenize(String text) {
        List<String[]> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        int at = 0;
        while (at < text.length()) {
            if (text.substring(at).trim().isEmpty()) break;
            if (!m.find(at) || m.start() != at || m.end() == at) throw new IllegalArgumentException(text);
            at = m.end();
            if (m.group(1) != null) out.add(new String[]{"str", m.group(1)});
            else if (m.group(2) != null) out.add(new String[]{"num", m.group(2)});
            else if (m.group(3) != null) out.add(new String[]{"op", m.group(3)});
            else if (m.group(4) != null) out.add(new String[]{"paren", m.group(4)});
            else {
                String w = m.group(5);
                String lower = w.toLowerCase();
                if (lower.equals("not") || lower.equals("and") || lower.equals("or") || lower.equals("xor")
                        || lower.equals("eqv") || lower.equals("imp")) out.add(new String[]{"kw", lower});
                else out.add(new String[]{"word", w});
            }
        }
        return out;
    }

    private String[] peek() {
        return pos < tokens.size() ? tokens.get(pos) : new String[]{null, null};
    }

    private boolean take(String kind, String value) {
        String[] t = peek();
        if (kind.equals(t[0]) && (value == null || value.equals(t[1]))) {
            pos++;
            return true;
        }
        return false;
    }

    private Object binary(int level) {
        if (level == LEVELS.length) return negation();
        Object left = binary(level + 1);
        String op = LEVELS[level];
        while (take("kw", op)) {
            boolean right = truth(binary(level + 1));
            boolean a = truth(left);
            switch (op) {
                case "and": left = a && right; break;
                case "or": left = a || right; break;
                case "xor": left = a != right; break;
                case "eqv": left = a == right; break;
                default: left = !a || right; break;
            }
        }
        return left;
    }

    private Object negation() {
        if (take("kw", "not")) return !truth(negation());
        return comparison();
    }

    private Object comparison() {
        Object left = operand();
        String[] t = peek();
        if (!"op".equals(t[0])) return left;
        pos++;
        Object right = operand();
        return compare(left, t[1], right);
    }

    private Object operand() {
        String[] t = peek();
        pos++;
        if (t[0] == null) throw new IllegalArgumentException("operand expected");
        switch (t[0]) {
            case "paren":
                if (!"(".equals(t[1])) throw new IllegalArgumentException("operand expected");
                Object inner = binary(0);
                if (!take("paren", ")")) throw new IllegalArgumentException("unbalanced");
                return inner;
            case "str":
                return t[1].substring(1, t[1].length() - 1);
            case "num":
                return Integer.parseInt(t[1]);
            case "word": {
                String v = t[1];
                char sigil = "%$?&!".indexOf(v.charAt(0)) >= 0 ? v.charAt(0) : 0;
                String name = sigil != 0 ? v.substring(1) : v;
                if (sigil == '%') return "";
                if (sigil == '&' || sigil == '!') {
                    Integer s = featureState.apply(name);
                    return s != null ? s : -1;
                }
                if (sigil == '$' || sigil == '?') {
                    Integer s = componentState.apply(name);
                    return s != null ? s : -1;
                }
                String found = props.get(name);
                return found == null ? "" : found;
            }
            default:
                throw new IllegalArgumentException("operand expected");
        }
    }

    private static Integer number(Object v) {
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof String && ((String) v).trim().matches("-?\\d+")) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object compare(Object left, String op, Object right) {
        boolean fold = op.startsWith("~");
        op = op.replace("~", "");
        Integer a = number(left), b = number(right);
        if (a != null && b != null) {
            switch (op) {
                case "=": return a.intValue() == b.intValue();
                case "<>": return a.intValue() != b.intValue();
                case "<": return a < b;
                case ">": return a > b;
                case "<=": return a <= b;
                case ">=": return a >= b;
                case "><": return (a & b) != 0;
                case "<<": return (a >> 16) == b;
                default: return (a & 0xFFFF) == b;
            }
        }
        if (left instanceof Integer || left instanceof Boolean || right instanceof Integer || right instanceof Boolean) {
            return op.equals("<>");
        }
        String x = String.valueOf(left), y = String.valueOf(right);
        if (fold) {
            x = x.toLowerCase();
            y = y.toLowerCase();
        }
        switch (op) {
            case "=": return x.equals(y);
            case "<>": return !x.equals(y);
            case "<": return x.compareTo(y) < 0;
            case ">": return x.compareTo(y) > 0;
            case "<=": return x.compareTo(y) <= 0;
            case ">=": return x.compareTo(y) >= 0;
            case "><": return x.contains(y);
            case "<<": return x.startsWith(y);
            default: return x.endsWith(y);
        }
    }

    static boolean truth(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Integer) return (Integer) v != 0;
        return v != null && !"".equals(v);
    }
}
