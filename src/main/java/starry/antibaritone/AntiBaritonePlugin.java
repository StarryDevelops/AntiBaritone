package starry.antibaritone;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Objects;

public final class AntiBaritonePlugin extends JavaPlugin {

    private BaritoneDetector detector;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.detector = new BaritoneDetector(this);
        Bukkit.getPluginManager().registerEvents(this.detector, this);
        Objects.requireNonNull(getCommand("antibaritone")).setExecutor(this);
        Objects.requireNonNull(getCommand("punishment")).setExecutor(this);
        getLogger().info("AntiBaritone enabled.");
    }

    @Override
    public void onDisable() {
        if (this.detector != null) {
            this.detector.clear();
        }
    }

    public void alert(String message) {
        if (getConfig().getBoolean("log-alerts", true)) {
            getLogger().warning(ChatColor.stripColor(message));
        }
        
        // Broadcast the alert to any staff online
        if (getConfig().getBoolean("broadcast-alerts", true)) {
            String colored = ChatColor.translateAlternateColorCodes('&', "&8[&cAntiBaritone&8] &7" + message);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("antibaritone.alert")) {
                    player.sendMessage(colored);
                }
            }
        }
    }

    public void handleDetection(Player player, String message, double score) {
        alert(message);

        // Only trigger the punishment if they've really crossed the threshold
        if (score < 100.0) {
            return;
        }

        String punishment = getConfig().getString("punishment", "warn_staff")
                .toLowerCase(Locale.ROOT)
                .replace("-", "_")
                .replace(" ", "_");

        String reason = getConfig().getString("punishment-reason", "Baritone bot detection");
        String coloredReason = ChatColor.translateAlternateColorCodes('&', "&c" + reason);
        
        // Execute the configured punishment
        if (punishment.equals("kick")) {
            Bukkit.getScheduler().runTask(this, () -> player.kickPlayer(ChatColor.RED + "Disconnected\n\n" + ChatColor.GRAY + "Reason: " + coloredReason));
        } else if (punishment.equals("ban")) {
            Bukkit.getScheduler().runTask(this, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + player.getName() + " " + reason);
            });
        } else if (punishment.equals("command")) {
            String customCommand = getConfig().getString("punishment-command", "kick {player} " + reason).replace("{player}", player.getName());
            Bukkit.getScheduler().runTask(this, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), customCommand);
            });
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antibaritone.command")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("punishment")) {
            return handlePunishmentCommand(sender, label, args);
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <status|reset|reload> [player]");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "AntiBaritone config reloaded.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " " + action + " <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player is not online.");
            return true;
        }

        if (action.equals("status")) {
            DetectorSnapshot snapshot = detector.snapshot(target.getUniqueId());
            sender.sendMessage(ChatColor.GOLD + "AntiBaritone status for " + target.getName() + ":");
            sender.sendMessage(ChatColor.YELLOW + "Score: " + ChatColor.WHITE + String.format(Locale.US, "%.2f", snapshot.score()));
            sender.sendMessage(ChatColor.YELLOW + "Samples: " + ChatColor.WHITE + snapshot.samples());
            sender.sendMessage(ChatColor.YELLOW + "Precise hits: " + ChatColor.WHITE + snapshot.preciseHits());
            sender.sendMessage(ChatColor.YELLOW + "Sharp snaps: " + ChatColor.WHITE + snapshot.sharpSnaps());
            sender.sendMessage(ChatColor.YELLOW + "Tunnel streaks: " + ChatColor.WHITE + snapshot.tunnelStreaks());
            sender.sendMessage(ChatColor.YELLOW + "Last reason: " + ChatColor.WHITE + snapshot.lastReason());
            return true;
        }

        if (action.equals("reset")) {
            detector.reset(target.getUniqueId());
            sender.sendMessage(ChatColor.GREEN + "Reset AntiBaritone evidence for " + target.getName() + ".");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <status|reset|reload> [player]");
        return true;
    }

    private boolean handlePunishmentCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Current punishment: " + ChatColor.WHITE + getPunishmentDisplay());
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <kick|ban|command|warn staff>");
            return true;
        }

        String requested = String.join(" ", args).toLowerCase(Locale.ROOT).trim();
        String normalized = requested.replace("-", "_").replace(" ", "_");
        
        // People type this in different ways, so let's just normalize it
        if (normalized.equals("warn") || normalized.equals("warnstaff")) {
            normalized = "warn_staff";
        }

        if (!normalized.equals("kick") && !normalized.equals("ban") && !normalized.equals("warn_staff") && !normalized.equals("command")) {
            sender.sendMessage(ChatColor.RED + "Invalid punishment. Use: kick, ban, command, or warn staff.");
            return true;
        }

        getConfig().set("punishment", normalized);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "AntiBaritone punishment set to " + ChatColor.WHITE + displayPunishment(normalized) + ChatColor.GREEN + ".");
        return true;
    }

    private String getPunishmentDisplay() {
        return displayPunishment(getConfig().getString("punishment", "warn_staff"));
    }

    private String displayPunishment(String punishment) {
        if (punishment == null) {
            return "warn staff";
        }
        return punishment.equalsIgnoreCase("warn_staff") ? "warn staff" : punishment.toLowerCase(Locale.ROOT);
    }
}
