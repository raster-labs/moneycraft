package com.raster.moneycraft.extension.service;

import com.raster.moneycraft.extension.dto.ExtensionDtos;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Bounded pure FEEL expression & OMG DMN 1.6 decision-table evaluator (EXT-002 / EXT-004).
 * Uses {@link BigDecimal} exclusively for all numeric values and operations (zero float/double),
 * enforces maximum AST depth {@value #MAX_AST_DEPTH} and evaluation step budget {@value #MAX_STEP_BUDGET},
 * and forbids reflection, class loading, process execution, filesystem access, and networking.
 */
@Component
public class BoundedFeelRuleEvaluator {

    public static final int MAX_EXPRESSION_LENGTH = 4096;
    public static final int MAX_AST_DEPTH = 16;
    public static final int MAX_STEP_BUDGET = 500;

    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
        "class", "getclass", "forname", "runtime", "processbuilder",
        "system", "thread", "classloader", "reflect", "socket", "http", "file", "files", "paths"
    );

    private static final Set<String> SUPPORTED_HIT_POLICIES = Set.of("UNIQUE", "FIRST", "COLLECT");

    public ParsedFeelExpression compileAndValidate(String rawExpression) {
        if (rawExpression == null || rawExpression.isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-EMPTY",
                "Empty FEEL Expression",
                "Rule expression must be non-empty."
            );
        }
        if (rawExpression.length() > MAX_EXPRESSION_LENGTH) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-TOO-LONG",
                "FEEL Expression Exceeds Length Bound",
                "Expression length " + rawExpression.length() + " exceeds limit " + MAX_EXPRESSION_LENGTH + "."
            );
        }
        List<Token> tokens = tokenize(rawExpression);
        Parser parser = new Parser(tokens);
        AstNode root = parser.parseExpression(1);
        parser.expectEnd();
        return new ParsedFeelExpression(rawExpression, root, parser.maxObservedDepth);
    }

    public EvaluationOutcome evaluateExpression(String rawExpression, Map<String, Object> context) {
        ParsedFeelExpression compiled = compileAndValidate(rawExpression);
        StepBudget budget = new StepBudget(MAX_STEP_BUDGET);
        Object value = evalNode(compiled.root(), context == null ? Map.of() : context, budget);
        return new EvaluationOutcome(value, compiled.astDepth(), budget.consumed());
    }

    public DecisionTableOutcome evaluateDecisionTable(
        ExtensionDtos.DmnDecisionTableDto table,
        Map<String, Object> context
    ) {
        validateDecisionTableStructure(table);
        String hitPolicy = normalizeHitPolicy(table.hitPolicy());
        Map<String, Object> safeCtx = context == null ? Map.of() : context;
        StepBudget budget = new StepBudget(MAX_STEP_BUDGET);
        int maxDepth = 1;

        List<Object> evaluatedInputs = new ArrayList<>(table.inputExpressions().size());
        for (String inputExpr : table.inputExpressions()) {
            ParsedFeelExpression compiledInput = compileAndValidate(inputExpr);
            maxDepth = Math.max(maxDepth, compiledInput.astDepth());
            evaluatedInputs.add(evalNode(compiledInput.root(), safeCtx, budget));
        }

        List<String> matchedRuleIds = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<String> messages = new ArrayList<>();

        for (int rIdx = 0; rIdx < table.rules().size(); rIdx++) {
            ExtensionDtos.DmnDecisionRuleRowDto row = table.rules().get(rIdx);
            boolean rowMatches = true;
            for (int cIdx = 0; cIdx < evaluatedInputs.size(); cIdx++) {
                String unarySpec = cIdx < row.inputEntries().size() ? row.inputEntries().get(cIdx) : "-";
                DepthHolder depthHolder = new DepthHolder();
                boolean cellMatches = matchesUnaryTests(
                    evaluatedInputs.get(cIdx),
                    unarySpec,
                    safeCtx,
                    budget,
                    depthHolder
                );
                maxDepth = Math.max(maxDepth, depthHolder.maxDepth);
                if (!cellMatches) {
                    rowMatches = false;
                    break;
                }
            }
            if (rowMatches) {
                String ruleId = row.ruleId() == null || row.ruleId().isBlank()
                    ? "R" + (rIdx + 1)
                    : row.ruleId().trim();
                matchedRuleIds.add(ruleId);
                String out = row.outcome() == null || row.outcome().isBlank()
                    ? "ALLOW"
                    : row.outcome().trim().toUpperCase(Locale.ROOT);
                outcomes.add(out);
                if (row.message() != null && !row.message().isBlank()) {
                    messages.add(row.message().trim());
                }
                if ("FIRST".equals(hitPolicy)) {
                    break;
                }
            }
        }

        if ("UNIQUE".equals(hitPolicy) && matchedRuleIds.size() > 1) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-DMN-UNIQUE-CONFLICT",
                "DMN UNIQUE Hit Policy Conflict",
                "Decision table '" + table.tableCode() + "' matched multiple rules under UNIQUE policy: "
                    + matchedRuleIds + "."
            );
        }

        String aggregateOutcome = "ALLOW";
        for (String out : outcomes) {
            if ("BLOCK".equals(out) || "REJECT".equals(out)) {
                aggregateOutcome = "BLOCK";
                break;
            }
            if ("WARN".equals(out) || "WARNING".equals(out)) {
                aggregateOutcome = "WARN";
            }
        }

        return new DecisionTableOutcome(
            List.copyOf(matchedRuleIds),
            aggregateOutcome,
            List.copyOf(messages),
            maxDepth,
            budget.consumed()
        );
    }

    public void validateDecisionTableStructure(ExtensionDtos.DmnDecisionTableDto table) {
        if (table == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-DMN-INVALID",
                "Decision Table Required",
                "Decision table definition must not be null."
            );
        }
        String hitPolicy = normalizeHitPolicy(table.hitPolicy());
        if (!SUPPORTED_HIT_POLICIES.contains(hitPolicy)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-DMN-HIT-POLICY-UNSUPPORTED",
                "Unsupported DMN Hit Policy",
                "Supported hit policies are UNIQUE, FIRST, and COLLECT."
            );
        }
        if (table.inputExpressions() == null || table.inputExpressions().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-DMN-INPUTS-REQUIRED",
                "Decision Table Inputs Required",
                "Decision table must declare at least one inputExpression."
            );
        }
        for (String expr : table.inputExpressions()) {
            compileAndValidate(expr);
        }
        if (table.rules() == null || table.rules().isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-DMN-RULES-REQUIRED",
                "Decision Table Rules Required",
                "Decision table must declare at least one rule row."
            );
        }
        for (ExtensionDtos.DmnDecisionRuleRowDto row : table.rules()) {
            if (row.inputEntries() == null || row.inputEntries().size() != table.inputExpressions().size()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-DMN-ARITY-MISMATCH",
                    "Decision Table Column Arity Mismatch",
                    "Every DMN rule row must have exactly " + table.inputExpressions().size() + " inputEntries."
                );
            }
        }
    }

    public static String formatTypedResult(Object val) {
        if (val == null) {
            return "null";
        }
        if (val instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().scale() < 0
                ? bd.setScale(0).toPlainString()
                : bd.toPlainString();
        }
        return val.toString();
    }

    public static String describeResultType(Object val) {
        if (val == null) {
            return "NULL";
        }
        if (val instanceof BigDecimal) {
            return "EXACT_DECIMAL";
        }
        if (val instanceof Boolean) {
            return "BOOLEAN";
        }
        if (val instanceof LocalDate) {
            return "DATE";
        }
        if (val instanceof List<?>) {
            return "LIST";
        }
        return "TEXT";
    }

    private static String normalizeHitPolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "FIRST";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "U" -> "UNIQUE";
            case "F" -> "FIRST";
            case "C" -> "COLLECT";
            default -> u;
        };
    }

    private boolean matchesUnaryTests(
        Object inputVal,
        String rawUnarySpec,
        Map<String, Object> ctx,
        StepBudget budget,
        DepthHolder depthHolder
    ) {
        budget.step();
        if (rawUnarySpec == null || rawUnarySpec.isBlank() || "-".equals(rawUnarySpec.trim())) {
            return true;
        }
        String trimmed = rawUnarySpec.trim();
        if (trimmed.regionMatches(true, 0, "not(", 0, 4) && trimmed.endsWith(")")) {
            String inner = trimmed.substring(4, trimmed.length() - 1);
            return !matchesUnaryTests(inputVal, inner, ctx, budget, depthHolder);
        }

        List<String> alternatives = splitTopLevelCommas(trimmed);
        for (String alt : alternatives) {
            String item = alt.trim();
            if (item.isEmpty() || "-".equals(item)) {
                return true;
            }
            if (matchesSingleUnaryTest(inputVal, item, ctx, budget, depthHolder)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSingleUnaryTest(
        Object inputVal,
        String testSpec,
        Map<String, Object> ctx,
        StepBudget budget,
        DepthHolder depthHolder
    ) {
        budget.step();
        if ((testSpec.startsWith("[") || testSpec.startsWith("("))
            && (testSpec.endsWith("]") || testSpec.endsWith(")"))
            && testSpec.contains("..")) {
            ParsedFeelExpression rangeExpr = compileAndValidate(testSpec);
            depthHolder.maxDepth = Math.max(depthHolder.maxDepth, rangeExpr.astDepth());
            Object rangeObj = evalNode(rangeExpr.root(), ctx, budget);
            if (rangeObj instanceof FeelRange range) {
                return range.contains(inputVal);
            }
            return false;
        }

        String op = null;
        String rhs = testSpec;
        String[] prefixes = {"<=", ">=", "!=", "<>", "==", "<", ">", "="};
        for (String p : prefixes) {
            if (testSpec.startsWith(p)) {
                op = p;
                rhs = testSpec.substring(p.length()).trim();
                break;
            }
        }

        ParsedFeelExpression rhsExpr = compileAndValidate(rhs);
        depthHolder.maxDepth = Math.max(depthHolder.maxDepth, rhsExpr.astDepth());
        Object rhsVal = evalNode(rhsExpr.root(), ctx, budget);
        if (op == null || "=".equals(op) || "==".equals(op)) {
            return valuesEqual(inputVal, rhsVal);
        }
        if ("!=".equals(op) || "<>".equals(op)) {
            return !valuesEqual(inputVal, rhsVal);
        }
        int cmp = compareOrderedValues(inputVal, rhsVal);
        return switch (op) {
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            default -> false;
        };
    }

    private static List<String> splitTopLevelCommas(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inDouble = false;
        boolean inSingle = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' && !inSingle && (i == 0 || text.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            } else if (ch == '\'' && !inDouble && (i == 0 || text.charAt(i - 1) != '\\')) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle) {
                if (ch == '(' || ch == '[') {
                    depth++;
                } else if (ch == ')' || ch == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (ch == ',' && depth == 0) {
                    parts.add(text.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private Object evalNode(AstNode node, Map<String, Object> ctx, StepBudget budget) {
        budget.step();
        return switch (node) {
            case LiteralNode lit -> lit.value();
            case VariableNode varNode -> resolvePath(ctx, varNode.path());
            case UnaryMinusNode neg -> toBigDecimal(evalNode(neg.operand(), ctx, budget)).negate();
            case NotNode notNode -> !toBoolean(evalNode(notNode.operand(), ctx, budget));
            case ListLiteralNode listNode -> {
                List<Object> vals = new ArrayList<>(listNode.elements().size());
                for (AstNode el : listNode.elements()) {
                    vals.add(evalNode(el, ctx, budget));
                }
                yield List.copyOf(vals);
            }
            case RangeNode rangeNode -> new FeelRange(
                evalNode(rangeNode.lower(), ctx, budget),
                rangeNode.lowerInclusive(),
                evalNode(rangeNode.upper(), ctx, budget),
                rangeNode.upperInclusive()
            );
            case InNode inNode -> {
                Object target = evalNode(inNode.left(), ctx, budget);
                Object container = evalNode(inNode.right(), ctx, budget);
                if (container instanceof FeelRange range) {
                    yield range.contains(target);
                }
                if (container instanceof Collection<?> col) {
                    boolean matched = false;
                    for (Object item : col) {
                        budget.step();
                        if (valuesEqual(target, item)) {
                            matched = true;
                            break;
                        }
                    }
                    yield matched;
                }
                yield valuesEqual(target, container);
            }
            case BinaryNode bin -> evalBinary(bin, ctx, budget);
            case FunctionCallNode fn -> evalFunction(fn, ctx, budget);
        };
    }

    private Object evalBinary(BinaryNode bin, Map<String, Object> ctx, StepBudget budget) {
        String op = bin.operator();
        if ("AND".equals(op)) {
            boolean left = toBoolean(evalNode(bin.left(), ctx, budget));
            return left && toBoolean(evalNode(bin.right(), ctx, budget));
        }
        if ("OR".equals(op)) {
            boolean left = toBoolean(evalNode(bin.left(), ctx, budget));
            return left || toBoolean(evalNode(bin.right(), ctx, budget));
        }

        Object leftVal = evalNode(bin.left(), ctx, budget);
        Object rightVal = evalNode(bin.right(), ctx, budget);

        return switch (op) {
            case "+" -> {
                if (leftVal instanceof String || rightVal instanceof String) {
                    yield Objects.toString(leftVal, "") + Objects.toString(rightVal, "");
                }
                yield toBigDecimal(leftVal).add(toBigDecimal(rightVal));
            }
            case "-" -> toBigDecimal(leftVal).subtract(toBigDecimal(rightVal));
            case "*" -> toBigDecimal(leftVal).multiply(toBigDecimal(rightVal));
            case "=", "==" -> valuesEqual(leftVal, rightVal);
            case "!=", "<>" -> !valuesEqual(leftVal, rightVal);
            case "<" -> compareOrderedValues(leftVal, rightVal) < 0;
            case "<=" -> compareOrderedValues(leftVal, rightVal) <= 0;
            case ">" -> compareOrderedValues(leftVal, rightVal) > 0;
            case ">=" -> compareOrderedValues(leftVal, rightVal) >= 0;
            default -> throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-OPERATOR-UNSUPPORTED",
                "Unsupported FEEL Operator",
                "Operator '" + op + "' is outside the bounded FEEL profile."
            );
        };
    }

    private Object evalFunction(FunctionCallNode fn, Map<String, Object> ctx, StepBudget budget) {
        String name = fn.name().toLowerCase(Locale.ROOT);
        List<Object> args = new ArrayList<>(fn.args().size());
        for (AstNode arg : fn.args()) {
            args.add(evalNode(arg, ctx, budget));
        }
        return switch (name) {
            case "not" -> {
                requireArity(name, args, 1);
                yield !toBoolean(args.getFirst());
            }
            case "startswith" -> {
                requireArity(name, args, 2);
                String s = args.get(0) == null ? "" : args.get(0).toString();
                String pfx = args.get(1) == null ? "" : args.get(1).toString();
                yield s.startsWith(pfx);
            }
            case "contains" -> {
                requireArity(name, args, 2);
                if (args.get(0) instanceof Collection<?> col) {
                    boolean found = false;
                    for (Object item : col) {
                        if (valuesEqual(item, args.get(1))) {
                            found = true;
                            break;
                        }
                    }
                    yield found;
                }
                String s = args.get(0) == null ? "" : args.get(0).toString();
                String sub = args.get(1) == null ? "" : args.get(1).toString();
                yield s.contains(sub);
            }
            case "endswith" -> {
                requireArity(name, args, 2);
                String s = args.get(0) == null ? "" : args.get(0).toString();
                String sfx = args.get(1) == null ? "" : args.get(1).toString();
                yield s.endsWith(sfx);
            }
            case "uppercase" -> {
                requireArity(name, args, 1);
                yield args.getFirst() == null ? "" : args.getFirst().toString().toUpperCase(Locale.ROOT);
            }
            case "lowercase" -> {
                requireArity(name, args, 1);
                yield args.getFirst() == null ? "" : args.getFirst().toString().toLowerCase(Locale.ROOT);
            }
            case "stringlength" -> {
                requireArity(name, args, 1);
                String s = args.getFirst() == null ? "" : args.getFirst().toString();
                yield BigDecimal.valueOf(s.length());
            }
            case "abs" -> {
                requireArity(name, args, 1);
                yield toBigDecimal(args.getFirst()).abs();
            }
            case "min" -> {
                requireArity(name, args, 2);
                yield toBigDecimal(args.get(0)).min(toBigDecimal(args.get(1)));
            }
            case "max" -> {
                requireArity(name, args, 2);
                yield toBigDecimal(args.get(0)).max(toBigDecimal(args.get(1)));
            }
            case "roundhalfup" -> {
                requireArity(name, args, 2);
                int scale = toBigDecimal(args.get(1)).intValueExact();
                yield toBigDecimal(args.get(0)).setScale(scale, RoundingMode.HALF_UP);
            }
            case "count" -> {
                requireArity(name, args, 1);
                if (args.getFirst() instanceof Collection<?> col) {
                    yield BigDecimal.valueOf(col.size());
                }
                yield args.getFirst() == null ? BigDecimal.ZERO : BigDecimal.ONE;
            }
            case "isempty", "isblank" -> {
                requireArity(name, args, 1);
                Object v = args.getFirst();
                if (v == null) {
                    yield true;
                }
                if (v instanceof String s) {
                    yield s.isBlank();
                }
                if (v instanceof Collection<?> c) {
                    yield c.isEmpty();
                }
                yield false;
            }
            case "isdefined" -> {
                requireArity(name, args, 1);
                Object v = args.getFirst();
                yield v != null && (!(v instanceof String s) || !s.isBlank());
            }
            case "date" -> {
                requireArity(name, args, 1);
                yield LocalDate.parse(Objects.toString(args.getFirst(), "").trim());
            }
            default -> throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-FUNCTION-FORBIDDEN",
                "Unapproved FEEL Function",
                "Function '" + fn.name() + "' is not admitted in MoneyCraft's bounded FEEL profile."
            );
        };
    }

    private static void requireArity(String fnName, List<Object> args, int expected) {
        if (args.size() != expected) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-ARITY",
                "Invalid Function Argument Count",
                "Function '" + fnName + "' expects " + expected + " argument(s) but received " + args.size() + "."
            );
        }
    }

    private static Object resolvePath(Map<String, Object> ctx, List<String> path) {
        Object cur = ctx;
        for (String segment : path) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            if (map.containsKey(segment)) {
                cur = map.get(segment);
            } else {
                Object ciMatch = null;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null && segment.equalsIgnoreCase(entry.getKey().toString())) {
                        ciMatch = entry.getValue();
                        break;
                    }
                }
                cur = ciMatch;
            }
            if (cur == null) {
                return null;
            }
        }
        return normalizeContextValue(cur);
    }

    private static Object normalizeContextValue(Object raw) {
        if (raw == null || raw instanceof BigDecimal || raw instanceof Boolean || raw instanceof LocalDate) {
            return raw;
        }
        if (raw instanceof Integer i) {
            return BigDecimal.valueOf(i.longValue());
        }
        if (raw instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (raw instanceof Short s) {
            return BigDecimal.valueOf(s.longValue());
        }
        if (raw instanceof Number) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-FLOAT-FORBIDDEN",
                "Binary Floating-Point Input Rejected",
                "Numeric rule inputs must be exact BigDecimal, integer, or exact decimal strings."
            );
        }
        if (raw instanceof Collection<?> col) {
            List<Object> normalized = new ArrayList<>(col.size());
            for (Object item : col) {
                normalized.add(normalizeContextValue(item));
            }
            return List.copyOf(normalized);
        }
        return raw.toString();
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val instanceof BigDecimal bd) {
            return bd;
        }
        if (val instanceof Integer i) {
            return BigDecimal.valueOf(i.longValue());
        }
        if (val instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (val instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.contains("e") || trimmed.contains("E")) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-EXPONENT-FORBIDDEN",
                    "Scientific Notation Rejected",
                    "FEEL monetary/numeric values must not use exponent notation: '" + trimmed + "'."
                );
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException ex) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-TYPE-MISMATCH",
                    "Invalid Exact Decimal Operand",
                    "Cannot convert '" + trimmed + "' to exact BigDecimal."
                );
            }
        }
        throw new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-EXT-FEEL-TYPE-MISMATCH",
            "Numeric Operand Required",
            "Expected exact decimal value in FEEL arithmetic expression but found "
                + (val == null ? "null" : val.getClass().getSimpleName()) + "."
        );
    }

    private static boolean toBoolean(Object val) {
        if (val instanceof Boolean b) {
            return b;
        }
        if (val == null) {
            return false;
        }
        throw new MoneyCraftException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MC-EXT-FEEL-BOOLEAN-REQUIRED",
            "Boolean Expression Required",
            "Expected boolean condition result in FEEL rule, got '" + val + "'."
        );
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        BigDecimal leftNum = tryCoerceDecimal(left);
        BigDecimal rightNum = tryCoerceDecimal(right);
        if (leftNum != null && rightNum != null) {
            return leftNum.compareTo(rightNum) == 0;
        }
        if (left instanceof LocalDate ldLeft) {
            LocalDate ldRight = tryCoerceDate(right);
            if (ldRight != null) {
                return ldLeft.equals(ldRight);
            }
        }
        if (right instanceof LocalDate ldRight) {
            LocalDate ldLeft = tryCoerceDate(left);
            if (ldLeft != null) {
                return ldLeft.equals(ldRight);
            }
        }
        if (left instanceof Boolean bLeft && right instanceof Boolean bRight) {
            return bLeft.equals(bRight);
        }
        return left.toString().equals(right.toString());
    }

    private static int compareOrderedValues(Object left, Object right) {
        if (left == null || right == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-NULL-COMPARISON",
                "Ordered Comparison on Null Input",
                "Cannot evaluate ordered comparison (<, <=, >, >=) when an operand is null."
            );
        }
        BigDecimal leftNum = tryCoerceDecimal(left);
        BigDecimal rightNum = tryCoerceDecimal(right);
        if (leftNum != null && rightNum != null) {
            return leftNum.compareTo(rightNum);
        }
        LocalDate leftDate = tryCoerceDate(left);
        LocalDate rightDate = tryCoerceDate(right);
        if (leftDate != null && rightDate != null) {
            return leftDate.compareTo(rightDate);
        }
        return left.toString().compareTo(right.toString());
    }

    private static BigDecimal tryCoerceDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Integer i) {
            return BigDecimal.valueOf(i.longValue());
        }
        if (v instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty() || t.contains("e") || t.contains("E")) {
                return null;
            }
            try {
                return new BigDecimal(t);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate tryCoerceDate(Object v) {
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof String s && s.trim().length() == 10) {
            try {
                return LocalDate.parse(s.trim());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    // =========================================================================
    // Lexer & Recursive-Descent Parser with Strict AST Depth Enforcement
    // =========================================================================

    private List<Token> tokenize(String expr) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char ch = expr.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                char quote = ch;
                int start = i + 1;
                i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char c = expr.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        sb.append(expr.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (c == quote) {
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(c);
                    i++;
                }
                if (!closed) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-FEEL-SYNTAX",
                        "Unterminated String Literal",
                        "Unterminated string starting at offset " + (start - 1) + "."
                    );
                }
                tokens.add(new Token(TokenType.STRING, sb.toString(), start - 1));
                continue;
            }
            if (Character.isDigit(ch)) {
                int start = i;
                while (i < n && Character.isDigit(expr.charAt(i))) {
                    i++;
                }
                if (i + 1 < n && expr.charAt(i) == '.' && expr.charAt(i + 1) != '.') {
                    i++;
                    while (i < n && Character.isDigit(expr.charAt(i))) {
                        i++;
                    }
                }
                if (i < n && (expr.charAt(i) == 'e' || expr.charAt(i) == 'E')) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-FEEL-EXPONENT-FORBIDDEN",
                        "Scientific Notation Forbidden",
                        "Exponent notation is forbidden in exact-decimal FEEL expressions."
                    );
                }
                tokens.add(new Token(TokenType.NUMBER, expr.substring(start, i), start));
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
                    i++;
                }
                String ident = expr.substring(start, i);
                String lower = ident.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_TOKENS.contains(lower)) {
                    throw new MoneyCraftException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "MC-EXT-FEEL-FORBIDDEN-CONSTRUCT",
                        "Forbidden Host Identifier in FEEL Rule",
                        "Identifier '" + ident + "' is forbidden in the sandboxed FEEL evaluator."
                    );
                }
                switch (lower) {
                    case "and" -> tokens.add(new Token(TokenType.AND, "AND", start));
                    case "or" -> tokens.add(new Token(TokenType.OR, "OR", start));
                    case "in" -> tokens.add(new Token(TokenType.IN, "IN", start));
                    case "true" -> tokens.add(new Token(TokenType.BOOLEAN, "true", start));
                    case "false" -> tokens.add(new Token(TokenType.BOOLEAN, "false", start));
                    case "null" -> tokens.add(new Token(TokenType.NULL, "null", start));
                    default -> tokens.add(new Token(TokenType.IDENT, ident, start));
                }
                continue;
            }
            if (i + 1 < n) {
                String two = expr.substring(i, i + 2);
                switch (two) {
                    case "<=", ">=", "!=", "<>", "==" -> {
                        tokens.add(new Token(TokenType.COMP, two, i));
                        i += 2;
                        continue;
                    }
                    case "&&" -> {
                        tokens.add(new Token(TokenType.AND, "AND", i));
                        i += 2;
                        continue;
                    }
                    case "||" -> {
                        tokens.add(new Token(TokenType.OR, "OR", i));
                        i += 2;
                        continue;
                    }
                    case ".." -> {
                        tokens.add(new Token(TokenType.DOTDOT, "..", i));
                        i += 2;
                        continue;
                    }
                    default -> {
                        // Fall through to single-char operator
                    }
                }
            }
            switch (ch) {
                case '<', '>', '=' -> tokens.add(new Token(TokenType.COMP, String.valueOf(ch), i));
                case '+' -> tokens.add(new Token(TokenType.PLUS, "+", i));
                case '-' -> tokens.add(new Token(TokenType.MINUS, "-", i));
                case '*' -> tokens.add(new Token(TokenType.STAR, "*", i));
                case '!' -> tokens.add(new Token(TokenType.BANG, "!", i));
                case '(' -> tokens.add(new Token(TokenType.LPAREN, "(", i));
                case ')' -> tokens.add(new Token(TokenType.RPAREN, ")", i));
                case '[' -> tokens.add(new Token(TokenType.LBRACKET, "[", i));
                case ']' -> tokens.add(new Token(TokenType.RBRACKET, "]", i));
                case ',' -> tokens.add(new Token(TokenType.COMMA, ",", i));
                case '.' -> tokens.add(new Token(TokenType.DOT, ".", i));
                default -> throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-SYNTAX",
                    "Unexpected Character in FEEL Expression",
                    "Unexpected character '" + ch + "' at offset " + i
                        + " (note: division '/' is intentionally excluded; use exact multiplication)."
                );
            }
            i++;
        }
        tokens.add(new Token(TokenType.EOF, "", n));
        return tokens;
    }

    private enum TokenType {
        NUMBER, STRING, BOOLEAN, NULL, IDENT,
        PLUS, MINUS, STAR, COMP, AND, OR, IN, BANG,
        LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, DOT, DOTDOT, EOF
    }

    private record Token(TokenType type, String text, int pos) {
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int idx;
        private int maxObservedDepth = 1;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private void enterDepth(int depth) {
            if (depth > MAX_AST_DEPTH) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-DEPTH-EXCEEDED",
                    "FEEL AST Depth Bound Exceeded",
                    "Expression nesting depth exceeds maximum allowed AST depth of " + MAX_AST_DEPTH + "."
                );
            }
            if (depth > maxObservedDepth) {
                maxObservedDepth = depth;
            }
        }

        AstNode parseExpression(int depth) {
            enterDepth(depth);
            return parseOr(depth);
        }

        private AstNode parseOr(int depth) {
            AstNode left = parseAnd(depth);
            while (peek().type() == TokenType.OR) {
                consume();
                enterDepth(depth + 1);
                AstNode right = parseAnd(depth + 1);
                left = new BinaryNode("OR", left, right);
            }
            return left;
        }

        private AstNode parseAnd(int depth) {
            AstNode left = parseComparison(depth);
            while (peek().type() == TokenType.AND) {
                consume();
                enterDepth(depth + 1);
                AstNode right = parseComparison(depth + 1);
                left = new BinaryNode("AND", left, right);
            }
            return left;
        }

        private AstNode parseComparison(int depth) {
            AstNode left = parseAdditive(depth);
            if (peek().type() == TokenType.IN) {
                consume();
                enterDepth(depth + 1);
                AstNode right = parseCollectionOrRange(depth + 1);
                return new InNode(left, right);
            }
            if (peek().type() == TokenType.COMP) {
                String op = consume().text();
                enterDepth(depth + 1);
                AstNode right = parseAdditive(depth + 1);
                return new BinaryNode(op, left, right);
            }
            return left;
        }

        private AstNode parseAdditive(int depth) {
            AstNode left = parseMultiplicative(depth);
            while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS) {
                String op = consume().text();
                enterDepth(depth + 1);
                AstNode right = parseMultiplicative(depth + 1);
                left = new BinaryNode(op, left, right);
            }
            return left;
        }

        private AstNode parseMultiplicative(int depth) {
            AstNode left = parseUnary(depth);
            while (peek().type() == TokenType.STAR) {
                consume();
                enterDepth(depth + 1);
                AstNode right = parseUnary(depth + 1);
                left = new BinaryNode("*", left, right);
            }
            return left;
        }

        private AstNode parseUnary(int depth) {
            enterDepth(depth);
            if (peek().type() == TokenType.MINUS) {
                consume();
                return new UnaryMinusNode(parseUnary(depth + 1));
            }
            if (peek().type() == TokenType.BANG) {
                consume();
                return new NotNode(parseUnary(depth + 1));
            }
            if (peek().type() == TokenType.IDENT
                && "not".equalsIgnoreCase(peek().text())
                && peekNext().type() != TokenType.LPAREN) {
                consume();
                return new NotNode(parseUnary(depth + 1));
            }
            return parsePrimary(depth);
        }

        private AstNode parseCollectionOrRange(int depth) {
            enterDepth(depth);
            if (peek().type() == TokenType.LBRACKET || peek().type() == TokenType.LPAREN) {
                return parsePrimary(depth);
            }
            return parsePrimary(depth);
        }

        private AstNode parsePrimary(int depth) {
            enterDepth(depth);
            Token tok = peek();
            return switch (tok.type()) {
                case NUMBER -> {
                    consume();
                    yield new LiteralNode(new BigDecimal(tok.text()));
                }
                case STRING -> {
                    consume();
                    yield new LiteralNode(tok.text());
                }
                case BOOLEAN -> {
                    consume();
                    yield new LiteralNode(Boolean.parseBoolean(tok.text()));
                }
                case NULL -> {
                    consume();
                    yield new LiteralNode(null);
                }
                case LPAREN -> {
                    consume();
                    AstNode first = parseExpression(depth + 1);
                    if (peek().type() == TokenType.DOTDOT) {
                        consume();
                        AstNode upper = parseExpression(depth + 1);
                        boolean upperInc = consumeRangeClosing();
                        yield new RangeNode(first, false, upper, upperInc);
                    }
                    expect(TokenType.RPAREN, "Expected ')'");
                    yield first;
                }
                case LBRACKET -> {
                    consume();
                    if (peek().type() == TokenType.RBRACKET) {
                        consume();
                        yield new ListLiteralNode(List.of());
                    }
                    AstNode first = parseExpression(depth + 1);
                    if (peek().type() == TokenType.DOTDOT) {
                        consume();
                        AstNode upper = parseExpression(depth + 1);
                        boolean upperInc = consumeRangeClosing();
                        yield new RangeNode(first, true, upper, upperInc);
                    }
                    List<AstNode> items = new ArrayList<>();
                    items.add(first);
                    while (peek().type() == TokenType.COMMA) {
                        consume();
                        items.add(parseExpression(depth + 1));
                    }
                    expect(TokenType.RBRACKET, "Expected ']' at end of list literal");
                    yield new ListLiteralNode(List.copyOf(items));
                }
                case IDENT -> {
                    consume();
                    if (peek().type() == TokenType.LPAREN) {
                        consume();
                        List<AstNode> args = new ArrayList<>();
                        if (peek().type() != TokenType.RPAREN) {
                            args.add(parseExpression(depth + 1));
                            while (peek().type() == TokenType.COMMA) {
                                consume();
                                args.add(parseExpression(depth + 1));
                            }
                        }
                        expect(TokenType.RPAREN, "Expected ')' after function arguments");
                        yield new FunctionCallNode(tok.text(), List.copyOf(args));
                    }
                    List<String> path = new ArrayList<>();
                    path.add(tok.text());
                    while (peek().type() == TokenType.DOT) {
                        consume();
                        Token seg = expect(TokenType.IDENT, "Expected field name after '.'");
                        path.add(seg.text());
                    }
                    yield new VariableNode(List.copyOf(path));
                }
                default -> throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-SYNTAX",
                    "Invalid FEEL Syntax",
                    "Unexpected token '" + tok.text() + "' at offset " + tok.pos() + "."
                );
            };
        }

        private boolean consumeRangeClosing() {
            if (peek().type() == TokenType.RBRACKET) {
                consume();
                return true;
            }
            if (peek().type() == TokenType.RPAREN) {
                consume();
                return false;
            }
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-FEEL-SYNTAX",
                "Invalid Range Literal",
                "Expected ']' or ')' to close FEEL range at offset " + peek().pos() + "."
            );
        }

        private Token peek() {
            return tokens.get(idx);
        }

        private Token peekNext() {
            return idx + 1 < tokens.size() ? tokens.get(idx + 1) : tokens.getLast();
        }

        private Token consume() {
            Token cur = tokens.get(idx);
            if (idx < tokens.size() - 1) {
                idx++;
            }
            return cur;
        }

        private Token expect(TokenType expected, String msg) {
            Token cur = peek();
            if (cur.type() != expected) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-SYNTAX",
                    "FEEL Syntax Error",
                    msg + " at offset " + cur.pos() + ", found '" + cur.text() + "'."
                );
            }
            return consume();
        }

        void expectEnd() {
            if (peek().type() != TokenType.EOF) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-SYNTAX",
                    "Trailing Tokens in FEEL Expression",
                    "Unexpected trailing token '" + peek().text() + "' at offset " + peek().pos() + "."
                );
            }
        }
    }

    private static final class StepBudget {
        private final int maxSteps;
        private int steps;

        StepBudget(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        void step() {
            steps++;
            if (steps > maxSteps) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-EXT-FEEL-STEP-BUDGET-EXCEEDED",
                    "FEEL Evaluation Step Budget Exceeded",
                    "Rule evaluation exceeded maximum step budget of " + maxSteps + " operations."
                );
            }
        }

        int consumed() {
            return steps;
        }
    }

    private static final class DepthHolder {
        private int maxDepth = 1;
    }

    private record FeelRange(Object lower, boolean lowerInclusive, Object upper, boolean upperInclusive) {
        boolean contains(Object candidate) {
            if (candidate == null) {
                return false;
            }
            int lowCmp = compareOrderedValues(candidate, lower);
            if (lowerInclusive ? lowCmp < 0 : lowCmp <= 0) {
                return false;
            }
            int highCmp = compareOrderedValues(candidate, upper);
            return upperInclusive ? highCmp <= 0 : highCmp < 0;
        }
    }

    public sealed interface AstNode permits LiteralNode, VariableNode, UnaryMinusNode, NotNode,
        ListLiteralNode, RangeNode, InNode, BinaryNode, FunctionCallNode {
    }

    public record LiteralNode(Object value) implements AstNode {
    }

    public record VariableNode(List<String> path) implements AstNode {
    }

    public record UnaryMinusNode(AstNode operand) implements AstNode {
    }

    public record NotNode(AstNode operand) implements AstNode {
    }

    public record ListLiteralNode(List<AstNode> elements) implements AstNode {
    }

    public record RangeNode(
        AstNode lower,
        boolean lowerInclusive,
        AstNode upper,
        boolean upperInclusive
    ) implements AstNode {
    }

    public record InNode(AstNode left, AstNode right) implements AstNode {
    }

    public record BinaryNode(String operator, AstNode left, AstNode right) implements AstNode {
    }

    public record FunctionCallNode(String name, List<AstNode> args) implements AstNode {
    }

    public record ParsedFeelExpression(String rawExpression, AstNode root, int astDepth) {
    }

    public record EvaluationOutcome(Object value, int astDepth, int stepsConsumed) {
    }

    public record DecisionTableOutcome(
        List<String> matchedRuleIds,
        String outcome,
        List<String> messages,
        int astDepth,
        int stepsConsumed
    ) {
    }
}
