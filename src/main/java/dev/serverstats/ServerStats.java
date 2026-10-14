package dev.serverstats;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public class ServerStats extends JavaPlugin implements Listener, TabExecutor {

    // ---- Layout constants ----
    private static final int STATS_PROFILE_SLOT = 21;
    private static final int STATS_BACK_SLOT = 23;
    private static final int PROFILE_BACK_SLOT = 22;
    private static final long CACHE_MS = 60_000L;
    private static final int TOP_AMOUNT = 5;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private Economy economy;
    private List<PlayerStats> cache = List.of();
    private long cacheTime = 0L;

    // ---- Data ----
    private record PlayerStats(OfflinePlayer player, double money, int kills, int deaths, long playTicks) {
        String name() {
            return player.getName() == null ? "Unknown" : player.getName();
        }
    }

    // ---- Inventory holders (used to recognise our GUIs) ----
    private static class StatsHolder implements InventoryHolder {
        Inventory inv;
        OfflinePlayer target;
        boolean fromLeaderboard;
        @Override public Inventory getInventory() { return inv; }
    }

    private static class ProfileHolder implements InventoryHolder {
        Inventory inv;
        OfflinePlayer target;
        boolean fromLeaderboard;
        @Override public Inventory getInventory() { return inv; }
    }

    private static class LeaderHolder implements InventoryHolder {
        Inventory inv;
        final Map<Integer, UUID> slotToPlayer = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    // ---- Lifecycle ----
    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        } else {
            getLogger().warning("Vault/economy plugin not found - money will show as 0.");
        }
        Objects.requireNonNull(getCommand("stats")).setExecutor(this);
        Objects.requireNonNull(getCommand("leaderboard")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ---- Commands ----
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("leaderboard")) {
            openLeaderboard(viewer);
            return true;
        }

        OfflinePlayer target;
        if (args.length == 0) {
            target = viewer;
        } else {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
                viewer.sendMessage("§cPlayer '" + args[0] + "' was not found.");
                return true;
            }
        }
        openStats(viewer, target, false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("stats") && args.length == 1) {
            String start = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(start))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    // ---- Stat loading ----
    private int stat(OfflinePlayer p, Statistic s) {
        try {
            return p.getStatistic(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private PlayerStats load(OfflinePlayer p) {
        double money = 0;
        if (economy != null) {
            try {
                money = economy.getBalance(p);
            } catch (Exception ignored) { }
        }
        return new PlayerStats(
                p, money,
                stat(p, Statistic.PLAYER_KILLS),
                stat(p, Statistic.DEATHS),
                stat(p, Statistic.PLAY_ONE_MINUTE));
    }

    /** All players that ever joined, cached for a minute so /leaderboard stays cheap. */
    private List<PlayerStats> getAll() {
        long now = System.currentTimeMillis();
        if (now - cacheTime > CACHE_MS) {
            List<PlayerStats> list = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                if (p.hasPlayedBefore() || p.isOnline()) list.add(load(p));
            }
            cache = list;
            cacheTime = now;
        }
        return cache;
    }

    // ---- Formatting ----
    private static String compact(double v) {
        double abs = Math.abs(v);
        if (abs >= 1e12) return String.format(Locale.US, "%.1fT", v / 1e12);
        if (abs >= 1e9) return String.format(Locale.US, "%.1fB", v / 1e9);
        if (abs >= 1e6) return String.format(Locale.US, "%.1fM", v / 1e6);
        if (abs >= 1e3) return String.format(Locale.US, "%.1fK", v / 1e3);
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.1f", v);
    }

    private static String playtime(long ticks) {
        long totalMin = ticks / 20 / 60;
        long d = totalMin / (60 * 24);
        long h = (totalMin / 60) % 24;
        long m = totalMin % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    // ---- Item helpers ----
    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack head(OfflinePlayer p, String name, String... lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(p);
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            it.setItemMeta(meta);
        }
        return it;
    }

    private void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    // ---- /stats GUI ----
    private void openStats(Player viewer, OfflinePlayer target, boolean fromLeaderboard) {
        PlayerStats s = load(target);
        StatsHolder holder = new StatsHolder();
        holder.target = target;
        holder.fromLeaderboard = fromLeaderboard;
        Inventory inv = Bukkit.createInventory(holder, 27, "§8Stats: " + s.name());
        holder.inv = inv;
        fill(inv);

        inv.setItem(4, head(target, "§e" + s.name(), "§7Player statistics"));
        inv.setItem(10, item(Material.EMERALD, "§fMoney: §a$ §f" + compact(s.money())));
        inv.setItem(12, item(Material.DIAMOND_SWORD, "§fKills: §c" + compact(s.kills())));
        inv.setItem(14, item(Material.SKELETON_SKULL, "§fDeaths: §7" + compact(s.deaths())));
        inv.setItem(16, item(Material.CLOCK, "§fPlaytime: §b" + playtime(s.playTicks())));
        inv.setItem(STATS_PROFILE_SLOT, item(Material.WRITABLE_BOOK, "§aView Full Profile",
                "§7Click to see more stats"));
        inv.setItem(STATS_BACK_SLOT, item(Material.BARRIER, "§cBack"));

        viewer.openInventory(inv);
    }

    // ---- Full profile GUI ----
    private void openProfile(Player viewer, OfflinePlayer target, boolean fromLeaderboard) {
        PlayerStats s = load(target);
        ProfileHolder holder = new ProfileHolder();
        holder.target = target;
        holder.fromLeaderboard = fromLeaderboard;
        Inventory inv = Bukkit.createInventory(holder, 27, "§8Profile: " + s.name());
        holder.inv = inv;
        fill(inv);

        double kd = s.deaths() == 0 ? s.kills() : (double) s.kills() / s.deaths();
        long firstPlayed = target.getFirstPlayed();
        long lastSeen = target.isOnline() ? System.currentTimeMillis() : target.getLastSeen();

        inv.setItem(4, head(target, "§e" + s.name(), target.isOnline() ? "§aOnline" : "§cOffline"));
        inv.setItem(10, item(Material.NETHERITE_SWORD, "§fK/D Ratio: §6" + String.format(Locale.US, "%.2f", kd)));
        inv.setItem(11, item(Material.ROTTEN_FLESH, "§fMobs Killed: §c" + compact(stat(target, Statistic.MOB_KILLS))));
        inv.setItem(12, item(Material.IRON_SWORD, "§fDamage Dealt: §c" + compact(stat(target, Statistic.DAMAGE_DEALT) / 10.0)));
        inv.setItem(13, item(Material.SHIELD, "§fDamage Taken: §7" + compact(stat(target, Statistic.DAMAGE_TAKEN) / 10.0)));
        inv.setItem(14, item(Material.LEATHER_BOOTS, "§fDistance Walked: §a" + compact(stat(target, Statistic.WALK_ONE_CM) / 100000.0) + " km"));
        inv.setItem(15, item(Material.RABBIT_FOOT, "§fJumps: §a" + compact(stat(target, Statistic.JUMP))));
        inv.setItem(16, item(Material.CLOCK, "§fPlaytime: §b" + playtime(s.playTicks())));
        inv.setItem(19, item(Material.EMERALD, "§fMoney: §a$ §f" + compact(s.money())));
        inv.setItem(20, item(Material.OAK_DOOR, "§fFirst Joined: §e" + (firstPlayed > 0 ? DATE_FMT.format(Instant.ofEpochMilli(firstPlayed)) : "Unknown")));
        inv.setItem(21, item(Material.COMPASS, "§fLast Seen: §e" + (lastSeen > 0 ? DATE_FMT.format(Instant.ofEpochMilli(lastSeen)) : "Unknown")));
        inv.setItem(PROFILE_BACK_SLOT, item(Material.BARRIER, "§cBack"));

        viewer.openInventory(inv);
    }

    // ---- /leaderboard GUI (4 columns) ----
    private void openLeaderboard(Player viewer) {
        List<PlayerStats> all = getAll();
        LeaderHolder holder = new LeaderHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Leaderboard");
        holder.inv = inv;
        fill(inv);

        addColumn(inv, holder, 1, item(Material.EMERALD, "§a§lMONEY", "§7Richest players"),
                all, PlayerStats::money, v -> "§a$ §f" + compact(v));
        addColumn(inv, holder, 3, item(Material.DIAMOND_SWORD, "§c§lKILLS", "§7Most player kills"),
                all, p -> p.kills(), v -> "§c" + compact(v));
        addColumn(inv, holder, 5, item(Material.CLOCK, "§b§lPLAYTIME", "§7Most time played"),
                all, p -> p.playTicks(), v -> "§b" + playtime((long) v));
        addColumn(inv, holder, 7, item(Material.SKELETON_SKULL, "§7§lDEATHS", "§7Most deaths"),
                all, p -> p.deaths(), v -> "§7" + compact(v));

        viewer.openInventory(inv);
    }

    private void addColumn(Inventory inv, LeaderHolder holder, int column, ItemStack header,
                           List<PlayerStats> all, ToDoubleFunction<PlayerStats> value,
                           java.util.function.DoubleFunction<String> format) {
        inv.setItem(column, header);
        List<PlayerStats> top = all.stream()
                .sorted(Comparator.comparingDouble(value).reversed())
                .limit(TOP_AMOUNT)
                .toList();
        for (int i = 0; i < top.size(); i++) {
            PlayerStats p = top.get(i);
            int slot = column + 9 * (i + 1);
            inv.setItem(slot, head(p.player(), "§e#" + (i + 1) + " §f" + p.name(),
                    format.apply(value.applyAsDouble(p)), "§8Click to view stats"));
            holder.slotToPlayer.put(slot, p.player().getUniqueId());
        }
    }

    // ---- Click handling ----
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder top = e.getView().getTopInventory().getHolder();
        if (!(top instanceof StatsHolder || top instanceof ProfileHolder || top instanceof LeaderHolder)) return;

        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player viewer)) return;
        int slot = e.getSlot();

        if (top instanceof StatsHolder h) {
            if (slot == STATS_PROFILE_SLOT) {
                later(() -> openProfile(viewer, h.target, h.fromLeaderboard));
            } else if (slot == STATS_BACK_SLOT) {
                if (h.fromLeaderboard) later(() -> openLeaderboard(viewer));
                else later(viewer::closeInventory);
            }
        } else if (top instanceof ProfileHolder h) {
            if (slot == PROFILE_BACK_SLOT) {
                later(() -> openStats(viewer, h.target, h.fromLeaderboard));
            }
        } else if (top instanceof LeaderHolder h) {
            UUID id = h.slotToPlayer.get(slot);
            if (id != null) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(id);
                later(() -> openStats(viewer, target, true));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder top = e.getView().getTopInventory().getHolder();
        if (top instanceof StatsHolder || top instanceof ProfileHolder || top instanceof LeaderHolder) {
            e.setCancelled(true);
        }
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }
}
