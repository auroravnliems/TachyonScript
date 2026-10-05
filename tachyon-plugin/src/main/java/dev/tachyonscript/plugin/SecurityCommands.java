package dev.tachyonscript.plugin;

import dev.tachyonscript.security.SecurityDecision;
import dev.tachyonscript.security.SecurityIncident;
import dev.tachyonscript.security.SecurityMessages;
import dev.tachyonscript.security.SecurityService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SecurityCommands {
    static final List<String> ACTIONS = List.of("status", "incidents", "inspect", "scan", "scanall", "quarantine",
            "approve", "restore", "enable", "disable", "test-webhook", "reload");
    private final TachyonPlugin plugin;
    SecurityCommands(TachyonPlugin plugin) { this.plugin = plugin; }

    void execute(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("tachyonscript.security.admin")) { send(sender, "Security administrator permission required."); return; }
        String action = arguments.length == 0 ? "status" : arguments[0].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) { send(sender, "Use /tys security " + String.join("|", ACTIONS)); return; }
        SecurityService service = plugin.engine().security();
        if (action.equals("status")) {
            send(sender, "Security: " + (service.options().enabled() ? "enabled" : "disabled") + "; Qwen: " + service.options().ai().enabled()
                    + "; required: " + service.options().ai().required() + "; quarantined/disabled: " + service.audit().blocked().size());
            send(sender, "Incidents: " + service.audit().incidents().size() + "; Discord pending: " + service.discord().pendingCount()
                    + (service.discord().lastFailure().isEmpty() ? "" : "; " + service.discord().lastFailure()));
            return;
        }
        if (action.equals("incidents")) {
            var incidents = service.audit().incidents();
            for (int index = Math.max(0, incidents.size() - 15); index < incidents.size(); index++) {
                SecurityIncident incident = incidents.get(index);
                sender.sendMessage(Component.text(incident.id() + " | " + incident.file() + " | " + incident.action(), NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/tys security inspect " + incident.id())));
            }
            if (incidents.isEmpty()) send(sender, "No security incidents.");
            return;
        }
        if (action.equals("inspect")) {
            if (arguments.length != 2) { send(sender, "Use /tys security inspect <incident>"); return; }
            SecurityIncident incident = service.audit().incident(arguments[1]);
            if (incident == null) { send(sender, "Unknown incident ID."); return; }
            for (String line : SecurityMessages.detail(incident, true).split("\n")) sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            for (var finding : incident.findings()) sender.sendMessage(Component.text(finding.location().display(), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.copyToClipboard(finding.location().display()))
                    .hoverEvent(Component.text(finding.codeSnippet())));
            return;
        }
        Set<String> scriptActions = Set.of("scan", "quarantine", "disable", "approve", "enable");
        if ((scriptActions.contains(action) || action.equals("restore")) && arguments.length != 2) {
            send(sender, "Use /tys security " + action + (action.equals("restore") ? " <incident>" : " <script>")); return;
        }
        String target = arguments.length > 1 ? arguments[1] : "";
        String actor = sender.getName();
        Set<String> affected;
        // Revocation is immediate, even if a previous provider request is still waiting on its worker.
        if (action.equals("disable") || action.equals("quarantine")) {
            try { affected = plugin.engine().securityAffected(target); service.blockNow(target); plugin.engine().revokeSecurity(affected); }
            catch (IllegalArgumentException e) { send(sender, "Unknown registered script. Use its exact relative .tys path."); return; }
        } else affected = Set.of();
        send(sender, "Security " + action + " started; pending revisions remain gated.");
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                service.register(plugin.scripts().read());
                switch (action) {
                    case "scan", "scanall" -> {
                        if (action.equals("scan")) service.registeredFile(target);
                        var batch = plugin.engine().scanSecurity(plugin.scripts());
                        send(sender, "Security scan complete: " + batch.reviews().size() + " reviewed; " + batch.denied().size()
                                + " denied; " + batch.pending().size() + " pending. Use /tys security incidents.");
                    }
                    case "disable", "quarantine" -> {
                        plugin.engine().securityDisable(target, action.equals("quarantine") ? SecurityDecision.QUARANTINE : SecurityDecision.DISABLE, actor, affected);
                        send(sender, target + " security-disabled. Source and report preserved.");
                    }
                    case "approve" -> {
                        service.registeredFile(target); plugin.engine().scanSecurity(plugin.scripts());
                        send(sender, "Approved exact hash and reviewed dependency context: " + service.approve(target, actor).sha256()
                                + ". Use restore/enable explicitly to release quarantine.");
                    }
                    case "restore" -> { service.restore(target, actor); reload(sender); }
                    case "enable" -> {
                        var blocked = service.audit().blocked().get(dev.tachyonscript.security.ScriptIdentity.of(target));
                        if (blocked != null) service.restore(blocked.id(), actor);
                        reload(sender);
                    }
                    case "test-webhook" -> {
                        if (!service.options().discord().enabled()) throw new IOException("Discord notifications are disabled in configuration");
                        var files = plugin.scripts().read();
                        String file = files.isEmpty() ? "security-service.tys" : files.getFirst().path();
                        if (files.isEmpty()) service.register(List.of(new dev.tachyonscript.language.source.SourceFile(file, "")));
                        send(sender, "Webhook test queued: " + service.testWebhook(file, actor).id());
                    }
                    case "reload" -> {
                        plugin.reloadConfig(); service.reloadOptions(SecuritySettings.from(plugin.getConfig()));
                        plugin.engine().scanSecurity(plugin.scripts());
                        send(sender, "Security configuration reloaded and sources rescanned.");
                    }
                    default -> throw new IllegalArgumentException("Unsupported security command");
                }
            } catch (IOException | RuntimeException e) {
                send(sender, service.options().redactor().redact("Security action failed: " + e.getMessage()));
                plugin.getLogger().warning(service.options().redactor().redact("Security admin action " + action + " failed: " + e.getMessage()));
            }
        });
    }
    private void reload(CommandSender sender) {
        if (!plugin.reloadAsync(Set.of(), report -> send(sender, "Reload after restore: " + report.summary())))
            send(sender, "Quarantine released; reload already running. Use /tys reload when it finishes.");
    }
    private void send(CommandSender sender, String text) {
        if (Bukkit.isPrimaryThread()) sender.sendMessage(Component.text(text, NamedTextColor.YELLOW));
        else if (plugin.isEnabled()) Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendMessage(Component.text(text, NamedTextColor.YELLOW)));
    }
    List<String> complete(String[] args) {
        if (args.length <= 1) return ACTIONS.stream().filter(s -> args.length == 0 || s.startsWith(args[0])).toList();
        if (args[0].equals("inspect") || args[0].equals("restore")) return plugin.engine().security().audit().incidents().stream()
                .map(SecurityIncident::id).filter(s -> s.startsWith(args[1])).toList();
        return plugin.engine().security().audit().incidents().stream().map(SecurityIncident::file)
                .distinct().filter(s -> s.startsWith(args[1])).toList();
    }
}
