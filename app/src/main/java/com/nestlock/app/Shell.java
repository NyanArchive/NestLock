package com.nestlock.app;

import java.util.List;
import java.util.Map;

/** Shell rendering helpers. All literal values are single-quoted. */
final class Shell {
    private Shell() {
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Renders one argv element. Placeholder values in {@code vars} are already
     * complete shell expressions (for example {@code "$KSUD"}), so they are
     * emitted verbatim; everything else is quoted as a literal.
     */
    static String renderArg(String arg, Map<String, String> vars) {
        String placeholder = vars.get(arg);
        if (placeholder != null) {
            return placeholder;
        }
        if (arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0 || arg.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("shell argument contains a control character");
        }
        return quote(arg);
    }

    static String renderCommand(List<String> argv, Map<String, String> vars) {
        StringBuilder out = new StringBuilder();
        for (String arg : argv) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(renderArg(arg, vars));
        }
        return out.toString();
    }
}
