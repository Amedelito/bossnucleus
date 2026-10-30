package com.radiactivo.nucleon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class NucleonBoss {
    private static class Orb { ItemDisplay d; Location pos; Vector v; Player tgt; int life = 110; }

    private final NucleonPlugin plugin;
    private final World world;
    private final Zombie body;
    private final BossBar bar;
    private final ItemDisplay core, ring;
    private final List<ItemDisplay> shards = new ArrayList<>();
    private final List<ItemDisplay> extras = new ArrayList<>();
    private final double dmg, maxHp;
    private final Random rnd = new Random();
    private BukkitTask loop;
    private int t = 0, phase = 1, cooldown = 100, busy = 0, lastAttack = -1;
    private boolean dead = false;

    public NucleonBoss(NucleonPlugin plugin, Location loc) {
        this.plugin = plugin;
        this.world = loc.getWorld();
        this.dmg = plugin.getConfig().getDouble("damage-multiplier", 1.0);
        this.maxHp = plugin.getConfig().getDouble("boss-health", 1500);
        final double hp = maxHp;
        body = world.spawn(loc, Zombie.class, z -> {
            z.setBaby(false);
            z.setInvisible(true);
            z.setSilent(true);
            z.setShouldBurnInDay(false);
            z.setCanPickupItems(false);
            z.setRemoveWhenFarAway(false);
            z.setPersistent(false);
            z.customName(Component.text("Nucleón"));
            z.setCustomNameVisible(false);
            z.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(hp);
            z.setHealth(hp);
            z.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(3.0);
            z.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(24);
            z.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.27);
            z.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(1.0);
            z.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(60);
            z.getAttribute(Attribute.GENERIC_STEP_HEIGHT).setBaseValue(2.0);
            z.getAttribute(Attribute.ZOMBIE_SPAWN_REINFORCEMENTS).setBaseValue(0);
        });
        core = display(loc, NucleonPlugin.CMD_CORE, Display.Billboard.CENTER);
        ring = display(loc, NucleonPlugin.CMD_RING, Display.Billboard.FIXED);
        for (int i = 0; i < 6; i++) shards.add(display(loc, NucleonPlugin.CMD_SHARD, Display.Billboard.CENTER));
        bar = Bukkit.createBossBar("§a§l☢ NUCLEÓN, Corazón del Reactor ☢", BarColor.GREEN, BarStyle.SEGMENTED_10);
        bar.setProgress(1.0);
        world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 4f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3);
        announce("§a§l☢ NUCLEÓN HA DESPERTADO ☢", "§7El reactor late de nuevo...");
        loop = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ---------- util ----------
    private ItemDisplay display(Location l, int cmd, Display.Billboard bb) {
        return world.spawn(l, ItemDisplay.class, d -> {
            d.setItemStack(NucleonPlugin.modelItem(Material.NETHER_STAR, cmd));
            d.setBillboard(bb);
            d.setPersistent(false);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(3f);
        });
    }

    private void tf(ItemDisplay d, float s, Quaternionf rot) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(s, s, s), new Quaternionf()));
    }

    private void dust(Location l, int r, int g, int b, float s) {
        world.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(r, g, b), s));
    }

    private void ringFx(Location c, double r, int pts, int R, int G, int B, float s) {
        for (int i = 0; i < pts; i++) {
            double a = Math.PI * 2 * i / pts;
            dust(c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), R, G, B, s);
        }
    }

    private boolean stopped() { return dead || !body.isValid(); }

    private List<Player> targets() {
        List<Player> l = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.getLocation().distanceSquared(body.getLocation()) > 60 * 60) continue;
            l.add(p);
        }
        return l;
    }

    private Player nearest() {
        Player best = null; double bd = Double.MAX_VALUE;
        for (Player p : targets()) {
            double d = p.getLocation().distanceSquared(body.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private void hit(Player p, double amount, Vector kb) {
        p.damage(amount * dmg, body);
        if (kb != null) p.setVelocity(p.getVelocity().add(kb));
    }

    private Vector away(Location from, Player p, double force, double up) {
        Vector v = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 0.01) v = new Vector(1, 0, 0);
        return v.normalize().multiply(force).setY(up);
    }

    private Location ground(Location l) {
        Location g = l.clone();
        for (int i = 0; i < 10 && g.getBlock().isPassable(); i++) g.add(0, -1, 0);
        g.setY(g.getBlockY() + 1.1);
        return g;
    }

    private void announce(String title, String sub) {
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(body.getLocation()) < 90 * 90)
                p.showTitle(Title.title(Component.text(title), Component.text(sub)));
        }
    }

    public boolean isBody(Entity e) { return e.getUniqueId().equals(body.getUniqueId()); }
    public boolean isAlive() { return !dead && body.isValid(); }

    // ---------- main loop ----------
    private void tick() {
        t++;
        if (stopped()) { if (!dead) remove(); return; }
        Location b = body.getLocation();
        Location c = b.clone().add(0, 3.3, 0);
        float pulse = 1f + 0.08f * (float) Math.sin(t / 6.0);
        float enr = phase == 3 ? 1.15f : 1f;
        core.teleport(c);
        tf(core, 4.2f * pulse * enr, new Quaternionf());
        ring.teleport(c);
        tf(ring, 11f, new Quaternionf().rotateY(t * (phase == 3 ? 0.1f : 0.06f)).rotateX((float) Math.PI / 2));
        for (int i = 0; i < shards.size(); i++) {
            double a = t * 0.05 * (phase == 3 ? 1.8 : 1) + i * Math.PI * 2 / shards.size();
            double y = Math.sin(t * 0.08 + i) * 0.8;
            ItemDisplay s = shards.get(i);
            s.teleport(c.clone().add(Math.cos(a) * 5.2, y, Math.sin(a) * 5.2));
            tf(s, 1.6f, new Quaternionf().rotateZ(t * 0.1f));
        }
        if (t % 3 == 0) ambient(c);
        if (t % 5 == 0) updateBar();

        double ratio = body.getHealth() / maxHp;
        int np = ratio > 0.66 ? 1 : ratio > 0.33 ? 2 : 3;
        if (np > phase) phaseChange(np);

        if (t % 20 == 0) { Player n = nearest(); if (n != null) body.setTarget(n); }
        if (busy > 0) { busy--; return; }
        if (cooldown > 0) { cooldown--; return; }
        Player tgt = nearest();
        if (tgt != null) chooseAttack(tgt);
    }

    private void ambient(Location c) {
        double a = t * 0.15;
        int R = phase == 3 ? 255 : 110, G = phase == 3 ? 90 : 255, B = phase == 3 ? 30 : 50;
        for (int i = 0; i < 3; i++) {
            double aa = a + i * 2.09;
            dust(c.clone().add(Math.cos(aa) * 3, Math.sin(t * 0.07 + i) * 2, Math.sin(aa) * 3), R, G, B, 1.6f);
        }
        world.spawnParticle(Particle.END_ROD, c, 1, 1.5, 1.5, 1.5, 0.01);
        if (phase == 3) world.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 2, 1.5, 2, 1.5, 0.02);
    }

    private void updateBar() {
        bar.setProgress(Math.max(0, Math.min(1, body.getHealth() / maxHp)));
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(body.getLocation()) < 80 * 80) bar.addPlayer(p); else bar.removePlayer(p);
        }
    }

    private void phaseChange(int np) {
        phase = np;
        busy = 100; cooldown = 60;
        body.setInvulnerable(true);
        body.setAI(false);
        Location c = body.getLocation().add(0, 3, 0);
        world.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 5f, 0.5f);
        world.playSound(c, Sound.ENTITY_WITHER_SPAWN, 3f, 0.7f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 4, 2, 2, 2);
        for (Player p : targets()) p.setVelocity(away(body.getLocation(), p, 1.8, 0.7));
        if (np == 2) {
            bar.setColor(BarColor.YELLOW);
            body.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.32);
            announce("§e§lFASE II", "§7Inestable... los orbes se liberan");
        } else {
            bar.setColor(BarColor.RED);
            body.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.4);
            announce("§c§lFASE III", "§4COLAPSO CRÍTICO");
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (stopped()) return;
            body.setInvulnerable(false);
            body.setAI(true);
        }, 80L);
    }

    private void chooseAttack(Player tgt) {
        int max = phase == 1 ? 3 : phase == 2 ? 4 : 5;
        int a;
        do { a = rnd.nextInt(max); } while (a == lastAttack && max > 1);
        lastAttack = a;
        cooldown = phase == 1 ? 70 : phase == 2 ? 50 : 35;
        switch (a) {
            case 0 -> pulse();
            case 1 -> rain();
            case 2 -> beam(tgt);
            case 3 -> orbs();
            default -> collapse(tgt);
        }
    }

    // ---------- ataques ----------
    /** Onda radiactiva expansiva: se esquiva saltando. */
    private void pulse() {
        busy = 55;
        final Location o = body.getLocation().clone();
        world.playSound(o, Sound.BLOCK_BEACON_ACTIVATE, 4f, 0.6f);
        new BukkitRunnable() {
            int k = 0; final Set<UUID> hitSet = new HashSet<>();
            public void run() {
                if (stopped()) { cancel(); return; }
                k++;
                if (k <= 25) { ringFx(o, 6 - k * 0.2, 24, 255, 255, 80, 1.2f); return; }
                if (k == 26) world.playSound(o, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
                int j = k - 25; double r = j * 1.0;
                ringFx(o, r, Math.max(24, (int) (r * 8)), 90, 255, 40, 2f);
                for (Player p : targets()) {
                    double d = Math.hypot(p.getX() - o.getX(), p.getZ() - o.getZ());
                    if (Math.abs(d - r) < 1.3 && p.getY() - o.getY() < 1.2 && hitSet.add(p.getUniqueId())) {
                        hit(p, 18, away(o, p, 1.4, 0.5));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1));
                    }
                }
                if (j >= 16) cancel();
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    /** Lluvia de uranio: marcas en el suelo que explotan. */
    private void rain() {
        busy = 55;
        final List<Location> marks = new ArrayList<>();
        int n = 0;
        for (Player p : targets()) { if (n++ >= 5) break; marks.add(ground(p.getLocation())); }
        if (phase == 3) for (int i = 0; i < 4; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 4 + rnd.nextDouble() * 10;
            marks.add(ground(body.getLocation().add(Math.cos(a) * d, 0, Math.sin(a) * d)));
        }
        new BukkitRunnable() {
            int k = 0;
            public void run() {
                if (stopped()) { cancel(); return; }
                k++;
                if (k <= 45) {
                    if (k == 1) for (Location m : marks) world.playSound(m, Sound.BLOCK_NOTE_BLOCK_PLING, 3f, 0.5f);
                    for (Location m : marks) {
                        ringFx(m, 3.2, 20, 255, 60, 60, 1.3f);
                        ringFx(m, 3.2 * (1 - k / 50.0) + 0.4, 12, 255, 200, 60, 1f);
                    }
                    return;
                }
                for (Location m : marks) {
                    world.strikeLightningEffect(m);
                    world.playSound(m, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.7f);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, m, 1);
                    world.spawnParticle(Particle.FLASH, m, 1);
                    for (double y = 0; y < 14; y += 0.5) {
                        dust(m.clone().add(0, y, 0), 120, 255, 50, 3f);
                        world.spawnParticle(Particle.END_ROD, m.clone().add(0, y, 0), 1, 0.4, 0, 0.4, 0.02);
                    }
                    for (Player p : targets()) {
                        double d = Math.hypot(p.getX() - m.getX(), p.getZ() - m.getZ());
                        if (d < 3.4 && Math.abs(p.getY() - m.getY()) < 4) hit(p, 24, new Vector(0, 0.9, 0));
                    }
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    /** Rayo del núcleo: carga, avisa con línea roja y dispara un haz perforante. */
    private void beam(Player tgt) {
        busy = 55;
        new BukkitRunnable() {
            int k = 0; Vector dir = new Vector(0, 0, 1);
            public void run() {
                if (stopped()) { cancel(); return; }
                k++;
                Location src = body.getLocation().add(0, 3.3, 0);
                if (k <= 30 && tgt.isOnline()) dir = tgt.getLocation().add(0, 1, 0).toVector().subtract(src.toVector()).normalize();
                if (k == 1) world.playSound(src, Sound.BLOCK_BEACON_POWER_SELECT, 4f, 0.5f);
                if (k <= 40) for (int i = 0; i < 6; i++)
                    dust(src.clone().add(rnd.nextGaussian() * 2.5, rnd.nextGaussian() * 2.5, rnd.nextGaussian() * 2.5), 255, 255, 80, 1.4f);
                if (k > 30 && k < 40) for (double d = 0; d < 45; d += 1) dust(src.clone().add(dir.clone().multiply(d)), 255, 50, 50, 0.8f);
                if (k == 40) {
                    world.playSound(src, Sound.ENTITY_WARDEN_SONIC_BOOM, 5f, 0.8f);
                    for (double d = 0; d < 45; d += 0.4) {
                        Location pt = src.clone().add(dir.clone().multiply(d));
                        world.spawnParticle(Particle.END_ROD, pt, 2, 0.05, 0.05, 0.05, 0.01);
                        dust(pt, 120, 255, 60, 3f);
                    }
                    for (Player p : targets()) {
                        Vector to = p.getLocation().add(0, 1, 0).toVector().subtract(src.toVector());
                        double proj = to.dot(dir);
                        if (proj > 0 && proj < 46 && to.clone().subtract(dir.clone().multiply(proj)).length() < 1.8) {
                            hit(p, 30, dir.clone().multiply(1.2).setY(0.5));
                            p.setFireTicks(80);
                        }
                    }
                }
                if (k >= 45) cancel();
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    /** Orbes teledirigidos (fase 2+). */
    private void orbs() {
        busy = 60;
        final int n = phase == 3 ? 10 : 6;
        final List<Orb> list = new ArrayList<>();
        new BukkitRunnable() {
            int k = 0, spawned = 0;
            public void run() {
                if (stopped()) { cancel(); return; }
                k++;
                List<Player> ps = targets();
                if (k % 4 == 0 && spawned < n && !ps.isEmpty()) {
                    spawned++;
                    Orb o = new Orb();
                    o.pos = body.getLocation().add(0, 3.3, 0);
                    o.v = new Vector(rnd.nextGaussian() * 0.5, 0.6, rnd.nextGaussian() * 0.5);
                    o.tgt = ps.get(rnd.nextInt(ps.size()));
                    o.d = display(o.pos, NucleonPlugin.CMD_ORB, Display.Billboard.CENTER);
                    extras.add(o.d);
                    list.add(o);
                    world.playSound(o.pos, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.6f);
                }
                Iterator<Orb> it = list.iterator();
                while (it.hasNext()) {
                    Orb o = it.next();
                    o.life--;
                    boolean remove = o.life <= 0;
                    if (o.tgt != null && o.tgt.isOnline() && !o.tgt.isDead()) {
                        Vector want = o.tgt.getLocation().add(0, 1, 0).toVector().subtract(o.pos.toVector()).normalize().multiply(0.65);
                        o.v = o.v.multiply(0.88).add(want.multiply(0.12));
                        if (o.tgt.getLocation().add(0, 1, 0).distanceSquared(o.pos) < 1.7) {
                            hit(o.tgt, 13, o.v.clone().normalize().multiply(0.8).setY(0.4));
                            world.spawnParticle(Particle.EXPLOSION, o.pos, 2);
                            world.playSound(o.pos, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1.4f);
                            remove = true;
                        }
                    }
                    if (!remove) {
                        o.pos.add(o.v);
                        o.d.teleport(o.pos);
                        tf(o.d, 1.5f + 0.2f * (float) Math.sin(o.life * 0.4), new Quaternionf());
                        dust(o.pos, 140, 255, 60, 1.6f);
                    } else {
                        world.spawnParticle(Particle.ELECTRIC_SPARK, o.pos, 10, 0.3, 0.3, 0.3, 0.1);
                        o.d.remove(); extras.remove(o.d); it.remove();
                    }
                }
                if (spawned >= n && list.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    /** Colapso (fase 3): se teletransporta sobre el objetivo y aplasta con onda enorme. */
    private void collapse(Player tgt) {
        busy = 55;
        final Location dest = tgt.getLocation().clone();
        new BukkitRunnable() {
            int k = 0;
            public void run() {
                if (stopped()) { cancel(); return; }
                k++;
                if (k == 1) world.playSound(body.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 5f, 0.4f);
                if (k <= 20 && tgt.isOnline()) { dest.setX(tgt.getX()); dest.setY(tgt.getY()); dest.setZ(tgt.getZ()); }
                if (k <= 35) {
                    ringFx(dest, 10, 40, 255, 40, 40, 1.4f);
                    ringFx(dest, 6, 28, 255, 150, 40, 1.4f);
                    ringFx(dest, 10 * (k / 35.0), 30, 255, 255, 255, 1.1f);
                    return;
                }
                if (k == 36) {
                    world.spawnParticle(Particle.PORTAL, body.getLocation().add(0, 3, 0), 80, 1, 2, 1, 0.5);
                    body.teleport(dest);
                    world.playSound(dest, Sound.ENTITY_GENERIC_EXPLODE, 6f, 0.5f);
                    world.playSound(dest, Sound.ENTITY_WITHER_BREAK_BLOCK, 4f, 0.6f);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, dest, 4, 3, 0.5, 3);
                    world.spawnParticle(Particle.FLASH, dest.clone().add(0, 2, 0), 2);
                    for (int r = 2; r <= 10; r += 2) ringFx(dest, r, r * 8, 255, 120, 30, 2.4f);
                    for (Player p : targets()) {
                        double d = p.getLocation().distance(dest);
                        if (d < 6) hit(p, 38, away(dest, p, 1.3, 1.1));
                        else if (d < 10.5) hit(p, 22, away(dest, p, 1.0, 0.7));
                        else continue;
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                    }
                }
                if (k >= 40) cancel();
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    // ---------- fin ----------
    public void onDeath() {
        if (dead) return;
        Location l = body.getLocation().add(0, 3, 0);
        for (Player p : world.getPlayers()) {
            p.sendMessage(Component.text("☢ Nucleón ha sido destruido. El reactor se apaga...", NamedTextColor.GREEN));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        world.playSound(l, Sound.ENTITY_ENDER_DRAGON_DEATH, 5f, 0.7f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 6, 3, 3, 3);
        for (int i = 0; i < 3; i++) {
            Firework f = world.spawn(l.clone().add(rnd.nextGaussian() * 2, 0, rnd.nextGaussian() * 2), Firework.class);
            org.bukkit.inventory.meta.FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.LIME, Color.YELLOW).withFade(Color.WHITE).trail(true).build());
            f.setFireworkMeta(m);
            f.detonate();
        }
        cleanup();
    }

    public void remove() {
        cleanup();
        if (body.isValid()) body.remove();
    }

    private void cleanup() {
        dead = true;
        if (loop != null) loop.cancel();
        core.remove(); ring.remove();
        shards.forEach(Entity::remove);
        extras.forEach(Entity::remove);
        extras.clear();
        bar.removeAll();
    }
}
