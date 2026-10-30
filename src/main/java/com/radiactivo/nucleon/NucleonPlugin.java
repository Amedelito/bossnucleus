package com.radiactivo.nucleon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class NucleonPlugin extends JavaPlugin implements Listener, TabExecutor {
    public static final int CMD_CORE = 7100, CMD_RING = 7101, CMD_SHARD = 7102,
            CMD_ORB = 7103, CMD_HEART = 7104, CMD_SWORD = 7105;
    private NucleonBoss boss;

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("nucleon").setExecutor(this);
        getCommand("nucleon").setTabCompleter(this);
    }

    @Override public void onDisable() { if (boss != null) boss.remove(); }

    public static ItemStack modelItem(Material m, int cmd) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.setCustomModelData(cmd);
        i.setItemMeta(im);
        return i;
    }

    public ItemStack heart() {
        ItemStack i = modelItem(Material.NETHER_STAR, CMD_HEART);
        ItemMeta im = i.getItemMeta();
        im.displayName(Component.text("☢ Corazón del Reactor", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        im.lore(List.of(Component.text("Aún late con energía radiactiva...", NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false)));
        i.setItemMeta(im);
        return i;
    }

    public ItemStack sword() {
        ItemStack i = modelItem(Material.NETHERITE_SWORD, CMD_SWORD);
        ItemMeta im = i.getItemMeta();
        im.displayName(Component.text("☢ Hoja del Colapso", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        im.lore(List.of(Component.text("Forjada con el núcleo de Nucleón.", NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false)));
        im.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey(this, "nucleon_dmg"), 15, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        im.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, new AttributeModifier(new NamespacedKey(this, "nucleon_spd"), -2.4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        im.setUnbreakable(true);
        i.setItemMeta(im);
        return i;
    }

    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (boss == null || !boss.isBody(e.getEntity())) return;
        switch (e.getCause()) {
            case FIRE, FIRE_TICK, LAVA, SUFFOCATION, DROWNING, FALL, WITHER, POISON, HOT_FLOOR -> e.setCancelled(true);
            default -> {}
        }
    }

    @EventHandler public void onDeath(EntityDeathEvent e) {
        if (boss == null || !boss.isBody(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(3000);
        e.getDrops().add(heart());
        e.getDrops().add(sword());
        boss.onDeath();
        boss = null;
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { s.sendMessage("§a/nucleon <spawn|kill|give>"); return true; }
        switch (a[0].toLowerCase()) {
            case "spawn" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
                if (boss != null && boss.isAlive()) { s.sendMessage("§cYa hay un Nucleón vivo."); return true; }
                boss = new NucleonBoss(this, p.getLocation());
            }
            case "kill" -> { if (boss != null) { boss.remove(); boss = null; s.sendMessage("§aNucleón eliminado."); } }
            case "give" -> {
                if (s instanceof Player p) { p.getInventory().addItem(heart(), sword()); }
            }
            default -> s.sendMessage("§a/nucleon <spawn|kill|give>");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 ? List.of("spawn", "kill", "give") : List.of();
    }
}
