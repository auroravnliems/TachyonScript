package dev.tachyonscript.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Discord embeds preserve authoritative incident evidence while keeping ordinary findings in one card. */
final class DiscordSecurityCard {
    private static final int MAX_FIELD = 1024;
    private static final int MAX_EMBED = 5900; // Leave room for continuation labels below Discord's 6000-character limit.
    private enum Format { TEXT, INLINE_CODE, CODE }
    private record Field(String name, String value, boolean inline) {
        int length() { return name.length() + value.length(); }
        Map<String, Object> json() { return Map.of("name", name, "value", value, "inline", inline); }
    }
    private DiscordSecurityCard() { }

    static List<String> render(SecurityIncident incident, boolean snippets) {
        List<String> result = new ArrayList<>();
        if (incident.findings().isEmpty()) card(incident, null, snippets, result);
        else for (SecurityFinding finding : incident.findings()) card(incident, finding, snippets, result);
        return List.copyOf(result);
    }

    private static void card(SecurityIncident incident, SecurityFinding finding, boolean snippets, List<String> output) {
        String title = title(incident);
        String category = finding == null ? incident.action() : finding.category().name();
        String reason = finding == null ? incident.summary() : finding.explanation();
        int descriptionEnd = end(reason, 0, 450);
        String description = "**" + text(category) + "**\n" + text(reason.substring(0, descriptionEnd));
        String footer = "TachyonScript • " + incident.id();
        List<Field> identity = new ArrayList<>();
        fields(identity, "Script", incident.file(), true, Format.INLINE_CODE);
        fields(identity, "Vị trí", finding == null ? "UNKNOWN — Precise source location unavailable."
                : finding.location().display(), true, Format.INLINE_CODE);
        fields(identity, "Mức độ", finding == null ? incident.severity().name() : finding.severity().name(), true, Format.INLINE_CODE);
        List<Field> detail = new ArrayList<>();
        fields(detail, "Hành động", incident.action(), true, Format.INLINE_CODE);
        fields(detail, "Độ tin cậy", finding == null ? "Không áp dụng"
                : String.format(Locale.ROOT, "%.0f%%", finding.confidence() * 100), true, Format.INLINE_CODE);
        fields(detail, "Phân tích", finding == null ? incident.actor() : analyzer(finding.origin()), true, Format.INLINE_CODE);
        if (finding != null) {
            String context = finding.handlerName();
            if (!finding.functionName().isBlank()) context += (context.isBlank() ? "" : " / ") + finding.functionName();
            fields(detail, "Handler / hàm", context, true, Format.INLINE_CODE);
            List<TaintStep> sources = finding.flow().stream().filter(step -> step.kind().equals("SOURCE")).distinct().toList();
            String source = sources.isEmpty() ? known(finding.source()) + " @ UNKNOWN"
                    : sources.stream().map(step -> step.expression() + " @ " + step.location().display())
                    .reduce((a, b) -> a + "\n" + b).orElseThrow();
            fields(detail, "Nguồn dữ liệu", source, true, Format.INLINE_CODE);
            fields(detail, "Đích thực thi", known(finding.sink()) + " @ " + finding.location().display(), true, Format.INLINE_CODE);
            if (snippets) fields(detail, "Đoạn mã", finding.codeSnippet(), false, Format.CODE);
            String flow = finding.flow().isEmpty() ? "UNKNOWN — Precise source location unavailable."
                    : finding.flow().stream().map(step -> step.expression() + " @ " + step.location().display() + " [" + step.kind() + "]")
                    .reduce((a, b) -> a + "\n→ " + b).orElseThrow();
            fields(detail, "Luồng dữ liệu", flow, false, Format.CODE);
        }
        if (descriptionEnd < reason.length()) fields(detail, "Lý do (tiếp)", reason.substring(descriptionEnd), false, Format.TEXT);
        fields(detail, "Phụ thuộc ảnh hưởng", incident.dependencies().isEmpty() ? "Không có" : String.join("\n", incident.dependencies()), false, Format.INLINE_CODE);
        fields(detail, "SHA-256", incident.sha256(), false, Format.INLINE_CODE);
        fields(detail, "Xem đầy đủ", "/tys security inspect " + incident.id(), false, Format.INLINE_CODE);
        List<List<Field>> pages = new ArrayList<>();
        List<Field> page = new ArrayList<>(identity);
        int overhead = title.length() + description.length() + footer.length() + 60;
        int identitySize = identity.stream().mapToInt(Field::length).sum();
        int size = overhead + identitySize;
        for (Field field : detail) {
            if (page.size() >= 25 || size + field.length() > MAX_EMBED) {
                pages.add(List.copyOf(page)); page = new ArrayList<>(identity); size = overhead + identitySize;
            }
            page.add(field); size += field.length();
        }
        pages.add(List.copyOf(page));
        for (int index = 0; index < pages.size(); index++) {
            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("title", title); embed.put("description", description);
            embed.put("color", color(incident, finding)); embed.put("timestamp", incident.timestamp().toString());
            embed.put("fields", pages.get(index).stream().map(Field::json).toList());
            embed.put("footer", Map.of("text", footer + (pages.size() == 1 ? "" : " • " + (index + 1) + "/" + pages.size())));
            output.add(SecurityJson.write(Map.of("content", "", "username", "TachyonScript Security", "embeds", List.of(embed),
                    "allowed_mentions", Map.of("parse", List.of()))));
        }
    }

    private static void fields(List<Field> fields, String name, String value, boolean inline, Format format) {
        String safe = switch (format) {
            case TEXT -> text(known(value));
            case INLINE_CODE -> known(value).replace('`', 'ˋ');
            case CODE -> known(value).replace("```", "``\u200b`");
        };
        String prefix = format == Format.CODE ? "```text\n" : format == Format.INLINE_CODE ? "`" : "";
        String suffix = format == Format.CODE ? "\n```" : format == Format.INLINE_CODE ? "`" : "";
        List<String> parts = new ArrayList<>();
        for (int offset = 0; offset < safe.length();) {
            int next = end(safe, offset, MAX_FIELD - prefix.length() - suffix.length());
            // Prefer whole lines, without losing text when one source line exceeds the field limit.
            if (next < safe.length()) {
                int newline = safe.lastIndexOf('\n', next - 1);
                if (newline > offset + (next - offset) / 2) next = newline + 1;
            }
            parts.add(prefix + safe.substring(offset, next) + suffix); offset = next;
        }
        for (int index = 0; index < parts.size(); index++) fields.add(new Field(name
                + (parts.size() == 1 ? "" : " (" + (index + 1) + "/" + parts.size() + ")"), parts.get(index), inline && parts.size() == 1));
    }

    private static int end(String value, int offset, int limit) {
        int end = Math.min(value.length(), offset + limit);
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return end;
    }
    private static String known(String value) { return value == null || value.isBlank() ? "UNKNOWN" : value; }
    private static String text(String value) {
        return known(value).replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("`", "\\`").replace("[", "\\[").replace("]", "\\]").replace("|", "\\|");
    }
    private static String analyzer(SecurityFinding.Origin origin) {
        return switch (origin) { case AI -> "Qwen"; case STATIC -> "Static"; case DEPENDENCY -> "Dependency"; case MANUAL -> "Admin"; };
    }
    private static String title(SecurityIncident incident) {
        String action = switch (incident.action()) {
            case "RESTORE", "ENABLE" -> "Đã khôi phục script";
            case "APPROVE" -> "Đã phê duyệt script";
            case "WEBHOOK_TEST" -> "Kiểm tra kết nối Discord";
            case "API_FAILURE" -> "Lỗi kiểm tra AI";
            case "SCANNER_FAILURE" -> "Lỗi bộ quét bảo mật";
            default -> incident.decision() == SecurityDecision.QUARANTINE ? "Đã cách ly script"
                    : incident.decision() == SecurityDecision.DISABLE ? "Đã vô hiệu hóa script" : "Cảnh báo bảo mật";
        };
        return "🛡️ " + action;
    }
    private static int color(SecurityIncident incident, SecurityFinding finding) {
        if (incident.action().equals("RESTORE") || incident.action().equals("ENABLE") || incident.action().equals("APPROVE")) return 0x57F287;
        return switch (finding == null ? incident.severity() : finding.severity()) {
            case INFO -> 0x5865F2; case LOW -> 0x57F287; case MEDIUM -> 0xFEE75C;
            case HIGH -> 0xE67E22; case CRITICAL -> 0xED4245;
        };
    }
}
