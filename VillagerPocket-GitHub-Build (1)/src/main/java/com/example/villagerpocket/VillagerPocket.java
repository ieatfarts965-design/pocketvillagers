package com.example.villagerpocket;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.util.*;

public final class VillagerPocket extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey pocketKey, tokenKey, menuKey;
    private final Random random = new Random();
    private File snapshotsFile;
    private YamlConfiguration snapshots;

    @Override public void onEnable() {
        saveDefaultConfig();
        pocketKey = new NamespacedKey(this, "pocket_villager");
        tokenKey = new NamespacedKey(this, "villager_token");
        menuKey = new NamespacedKey(this, "reroll_menu");
        snapshotsFile = new File(getDataFolder(), "villagers.yml");
        snapshots = YamlConfiguration.loadConfiguration(snapshotsFile);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("villagerpocket")).setExecutor(this);
    }

    @Override public void onDisable() { saveSnapshots(); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("villagerpocket.use")) return;
        event.setCancelled(true);
        if (villager.isDead() || villager.getPassengers().size() > 0) return;
        ItemStack item = createPocketItem(villager);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Your inventory is full.");
            return;
        }
        storeVillager(villager, item);
        villager.remove();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
        player.sendMessage(ChatColor.GREEN + "Villager picked up. Right-click the villager item to place it.");
    }

    private ItemStack createPocketItem(Villager v) {
        ItemStack item = new ItemStack(Material.VILLAGER_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "Pocket Villager");
        meta.setLore(List.of(ChatColor.GRAY + "Shift + right-click to pick up villagers",
                ChatColor.GRAY + "Right-click to place this villager",
                ChatColor.DARK_GRAY + "Profession: " + v.getProfession().name()));
        meta.getPersistentDataContainer().set(pocketKey, PersistentDataType.BYTE, (byte)1);
        String token = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, token);
        item.setItemMeta(meta);
        return item;
    }

    private void storeVillager(Villager v, ItemStack item) {
        String token = item.getItemMeta().getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
        String path = "villagers." + token;
        snapshots.set(path + ".profession", v.getProfession().name());
        snapshots.set(path + ".type", v.getVillagerType().name());
        snapshots.set(path + ".level", v.getVillagerLevel());
        snapshots.set(path + ".experience", v.getVillagerExperience());
        snapshots.set(path + ".custom-name", v.getCustomName());
        snapshots.set(path + ".custom-name-visible", v.isCustomNameVisible());
        snapshots.set(path + ".adult", !v.isAdult());
        snapshots.set(path + ".recipes", null);
        List<MerchantRecipe> recipes = v.getRecipes();
        for (int i=0; i<recipes.size(); i++) {
            MerchantRecipe r = recipes.get(i);
            String rp = path + ".recipes." + i;
            snapshots.set(rp + ".result", r.getResult().getType().name());
            snapshots.set(rp + ".result-amount", r.getResult().getAmount());
            snapshots.set(rp + ".uses", r.getUses());
            snapshots.set(rp + ".max-uses", r.getMaxUses());
            snapshots.set(rp + ".experience", r.getVillagerExperience());
            List<String> ingredients = new ArrayList<>();
            for (ItemStack ingredient : r.getIngredients())
                ingredients.add(ingredient.getType().name() + ":" + ingredient.getAmount());
            snapshots.set(rp + ".ingredients", ingredients);
        }
        saveSnapshots();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR &&
            event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !isPocket(item)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.isSneaking()) return;
        Block target = event.getClickedBlock();
        Location place = target == null ? player.getLocation().add(player.getLocation().getDirection().multiply(2))
                : target.getRelative(event.getBlockFace()).getLocation();
        place.add(0.5, 0, 0.5);
        if (!place.getBlock().isPassable() || !place.clone().add(0,1,0).getBlock().isPassable()) {
            player.sendMessage(ChatColor.RED + "There isn't enough room to place the villager.");
            return;
        }
        String token = item.getItemMeta().getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
        if (token == null || !snapshots.contains("villagers." + token)) {
            player.sendMessage(ChatColor.RED + "This villager item has no saved data.");
            return;
        }
        Villager v = place.getWorld().spawn(place, Villager.class, villager -> restoreVillager(villager, token));
        item.setAmount(item.getAmount() - 1);
        snapshots.set("villagers." + token, null);
        saveSnapshots();
        player.playSound(place, Sound.ENTITY_VILLAGER_AMBIENT, 1f, 1f);
        player.sendMessage(ChatColor.GREEN + "Villager placed.");
    }

    private boolean isPocket(ItemStack item) {
        if (item.getType() != Material.VILLAGER_SPAWN_EGG || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(pocketKey, PersistentDataType.BYTE);
    }

    private void restoreVillager(Villager v, String token) {
        String p = "villagers." + token;
        try { v.setProfession(Villager.Profession.valueOf(snapshots.getString(p + ".profession", "NONE"))); } catch (IllegalArgumentException ignored) {}
        try { v.setVillagerType(Villager.Type.valueOf(snapshots.getString(p + ".type", "PLAINS"))); } catch (IllegalArgumentException ignored) {}
        v.setVillagerLevel(Math.max(1, Math.min(5, snapshots.getInt(p + ".level", 1))));
        v.setVillagerExperience(Math.max(0, snapshots.getInt(p + ".experience", 0)));
        String name = snapshots.getString(p + ".custom-name");
        if (name != null) v.setCustomName(name);
        v.setCustomNameVisible(snapshots.getBoolean(p + ".custom-name-visible", false));
        if (snapshots.getBoolean(p + ".adult", false)) v.setBaby();
        ConfigurationSection rs = snapshots.getConfigurationSection(p + ".recipes");
        if (rs != null) {
            List<MerchantRecipe> list = new ArrayList<>();
            for (String key : rs.getKeys(false)) {
                String rp = p + ".recipes." + key;
                Material resultMat = Material.matchMaterial(snapshots.getString(rp + ".result", "EMERALD"));
                if (resultMat == null) resultMat = Material.EMERALD;
                ItemStack result = new ItemStack(resultMat, Math.max(1, snapshots.getInt(rp + ".result-amount", 1)));
                MerchantRecipe recipe = new MerchantRecipe(result, snapshots.getInt(rp + ".uses", 0),
                        Math.max(1, snapshots.getInt(rp + ".max-uses", 12)), true, 0, 0f);
                List<ItemStack> ingredients = new ArrayList<>();
                for (String spec : snapshots.getStringList(rp + ".ingredients")) {
                    String[] bits = spec.split(":");
                    Material m = bits.length > 0 ? Material.matchMaterial(bits[0]) : null;
                    int amount = bits.length > 1 ? parseInt(bits[1], 1) : 1;
                    if (m != null) ingredients.add(new ItemStack(m, Math.max(1, amount)));
                }
                if (!ingredients.isEmpty()) recipe.setIngredients(ingredients);
                list.add(recipe);
            }
            v.setRecipes(list);
        }
    }

    private int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; } }
    private void saveSnapshots() {
        try { snapshots.save(snapshotsFile); } catch (Exception e) { getLogger().severe("Could not save villager snapshots: " + e.getMessage()); }
    }

    @Override public boolean onCommand(org.bukkit.command.CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        if (!player.hasPermission("villagerpocket.use")) { player.sendMessage(ChatColor.RED + "No permission."); return true; }
        RayTraceResult trace = player.rayTraceEntities(6);
        if (trace == null || !(trace.getHitEntity() instanceof Villager villager)) {
            player.sendMessage(ChatColor.RED + "Look at a villager within 6 blocks.");
            return true;
        }
        openRerollMenu(player, villager);
        return true;
    }

    private void openRerollMenu(Player player, Villager villager) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Villager Trade Reroll");
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName(ChatColor.YELLOW + "Current villager");
        im.setLore(List.of(ChatColor.GRAY + "Profession: " + villager.getProfession().name(),
                ChatColor.GRAY + "Level: " + villager.getVillagerLevel(),
                ChatColor.GRAY + "Click the emerald to reroll a random offer."));
        info.setItemMeta(im);
        inv.setItem(13, info);
        ItemStack reroll = new ItemStack(Material.EMERALD);
        ItemMeta rm = reroll.getItemMeta();
        rm.setDisplayName(ChatColor.GREEN + "Reroll a random trade");
        rm.setLore(List.of(ChatColor.GRAY + "Replaces one trade offer with a random offer.",
                ChatColor.GRAY + "Cost: " + getConfig().getInt("reroll-cost", 0) + " emerald(s)"));
        rm.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, villager.getUniqueId().toString());
        reroll.setItemMeta(rm);
        inv.setItem(22, reroll);
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!ChatColor.stripColor(event.getView().getTitle()).equals("Villager Trade Reroll")) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String id = clicked.getItemMeta().getPersistentDataContainer().get(menuKey, PersistentDataType.STRING);
        if (id == null) return;
        Entity entity = Bukkit.getEntity(UUID.fromString(id));
        if (!(entity instanceof Villager villager) || villager.isDead()) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "That villager no longer exists.");
            return;
        }
        int cost = Math.max(0, getConfig().getInt("reroll-cost", 0));
        if (cost > 0 && !player.getInventory().containsAtLeast(new ItemStack(Material.EMERALD), cost)) {
            player.sendMessage(ChatColor.RED + "You need " + cost + " emerald(s).");
            return;
        }
        if (cost > 0) player.getInventory().removeItem(new ItemStack(Material.EMERALD, cost));
        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
        if (recipes.isEmpty()) {
            player.sendMessage(ChatColor.RED + "This villager has no trades to replace yet.");
            return;
        }
        int index = random.nextInt(recipes.size());
        recipes.set(index, randomRecipe());
        villager.setRecipes(recipes);
        player.closeInventory();
        player.sendMessage(ChatColor.GREEN + "Rerolled trade #" + (index + 1) + " for a new random offer.");
        player.openMerchant(villager, true);
    }

    private MerchantRecipe randomRecipe() {
        Material[] goods = {Material.BREAD, Material.COOKED_BEEF, Material.IRON_INGOT, Material.GOLD_INGOT,
                Material.DIAMOND, Material.EMERALD, Material.ARROW, Material.COOKED_COD, Material.GOLDEN_CARROT,
                Material.EXPERIENCE_BOTTLE, Material.ENDER_PEARL, Material.LAPIS_LAZULI, Material.REDSTONE,
                Material.BOOKSHELF, Material.SHIELD, Material.COOKED_PORKCHOP, Material.PUMPKIN_PIE};
        Material[] payment = {Material.WHEAT, Material.POTATO, Material.CARROT, Material.PAPER, Material.STICK,
                Material.STRING, Material.COAL, Material.IRON_INGOT, Material.EMERALD};
        Material in = payment[random.nextInt(payment.length)];
        Material out = goods[random.nextInt(goods.length)];
        if (in == out) out = Material.BREAD;
        int inAmount = 2 + random.nextInt(20);
        int outAmount = 1 + random.nextInt(4);
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(out, outAmount), 0, 12, true, 1, 0.05f);
        recipe.setIngredients(List.of(new ItemStack(in, inAmount)));
        return recipe;
    }
}
