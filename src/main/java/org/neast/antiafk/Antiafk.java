package org.neast.antiafk;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Antiafk extends JavaPlugin implements Listener {

    private final Map<UUID, BukkitRunnable> afkTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> isAfk      = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> afkByJoin  = new ConcurrentHashMap<>();
    private FileConfiguration config;

    private static final String PREFIX_KEY          = "prefix";
    private static final String MESSAGE_AFK         = "messages.afk";
    private static final String MESSAGE_NOT_AFK     = "messages.notAfk";
    private static final String MESSAGE_KICK_REASON = "messages.kickReason";
    private static final String TITLE_AFK           = "titles.afkTitle";
    private static final String SUBTITLE_AFK        = "titles.afkSubtitle";

    private String prefix;
    private String messageAfk;
    private String messageNotAfk;
    private String messageKickReason;
    private String titleAfk;
    private String subtitleAfk;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void reloadSettings() {
        config           = getConfig();
        prefix           = ChatColor.translateAlternateColorCodes('&', config.getString(PREFIX_KEY));
        messageAfk       = ChatColor.translateAlternateColorCodes('&', config.getString(MESSAGE_AFK));
        messageNotAfk    = ChatColor.translateAlternateColorCodes('&', config.getString(MESSAGE_NOT_AFK));
        messageKickReason= ChatColor.translateAlternateColorCodes('&', config.getString(MESSAGE_KICK_REASON));
        titleAfk         = ChatColor.translateAlternateColorCodes('&', config.getString(TITLE_AFK));
        subtitleAfk      = ChatColor.translateAlternateColorCodes('&', config.getString(SUBTITLE_AFK));
    }

    @Override
    public void onDisable() {
        afkTasks.values().forEach(BukkitRunnable::cancel);
        afkTasks.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"antiafk".equalsIgnoreCase(label)) return false;
        if (args.length > 0 && "reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("antiafk.reload")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            } else {
                reloadConfig();
                reloadSettings();
                sender.sendMessage(ChatColor.GREEN + "Configuration successfully reloaded!");
            }
            return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("antiafk.bypass")) return;
        scheduleAfkTask(p);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("antiafk.bypass")) return;

        UUID id = player.getUniqueId();

        // position
        boolean movedPos = event.getFrom().distanceSquared(event.getTo()) > 0.01;
        // tête (yaw/pitch)
        float dyaw   = Math.abs(event.getFrom().getYaw()   - event.getTo().getYaw());
        float dpitch = Math.abs(event.getFrom().getPitch() - event.getTo().getPitch());
        boolean movedHead = dyaw > 0.5f || dpitch > 0.5f;

        if (movedPos || movedHead) {
            // annule ancienne tâche et clear titre si AFK
            cancelAfkTask(id);
            if (isAfk.getOrDefault(id, false)) {
                if (afkByJoin.remove(id) == null) {
                    player.sendMessage(prefix + messageNotAfk);
                }
                isAfk.put(id, false);
                player.clearTitle();
            }
            // replanifie un nouveau check AFK
            scheduleAfkTask(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelAfkTask(id);
        isAfk.remove(id);
        afkByJoin.remove(id);
    }

    private void scheduleAfkTask(Player player) {
        UUID id = player.getUniqueId();
        // si déjà planifié, on ne fait rien
        if (afkTasks.containsKey(id)) return;

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                // passe AFK
                player.sendMessage(prefix + messageAfk);
                // titre persistant jusqu'à mouvement
                player.sendTitle(titleAfk, subtitleAfk, 10, 999999, 20);
                Sound snd = Sound.valueOf(config.getString("sounds.afkSound", "ENTITY_PLAYER_LEVELUP"));
                player.playSound(player.getLocation(), snd, 1.0f, 1.0f);
                isAfk.put(id, true);
                afkByJoin.put(id, afkByJoin.getOrDefault(id, false));
                kickPlayerIfAfk(player);
            }
        };
        afkTasks.put(id, task);
        task.runTaskLaterAsynchronously(this, config.getInt("afk.afkCheckDelayTicks", 200));
    }

    private void cancelAfkTask(UUID id) {
        BukkitRunnable old = afkTasks.remove(id);
        if (old != null) old.cancel();
    }

    private void kickPlayerIfAfk(Player player) {
        BukkitRunnable kicker = new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()
                        && !player.isDead()
                        && isAfk.getOrDefault(player.getUniqueId(), false)) {
                    player.kickPlayer(messageKickReason);
                }
                cancelAfkTask(player.getUniqueId());
            }
        };
        kicker.runTaskLater(this, config.getInt("afk.kickDelayTicks", 100));
    }
}
