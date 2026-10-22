package com.adongcraft.biaoji;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BiaojiPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String MARKER_TAG = "biaoji";
    private static final double LOOK_REACH = 160.0D;
    private static final double EMPTY_DISTANCE = 50.0D;
    private static final int LIFETIME_TICKS = 80;

    private final List<Ping> pings = new ArrayList<>();
    private final Map<UUID, Long> nextMark = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("biaoji");
        if (command != null) {
            command.setExecutor(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::tickPings, 10L, 10L);
        getLogger().info("标记已启用");
    }

    @Override
    public void onDisable() {
        for (Ping ping : pings) {
            if (ping.display.isValid()) {
                ping.display.remove();
            }
        }
        pings.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickBlock(PlayerPickBlockEvent event) {
        event.setCancelled(true);
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickEntity(PlayerPickEntityEvent event) {
        event.setCancelled(true);
        if (!isMarker(event.getEntity())) {
            mark(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwingAir(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR || !event.getPlayer().isSneaking()) {
            return;
        }
        mark(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("这个命令只能由玩家使用。", NamedTextColor.RED));
            return true;
        }
        mark(player);
        return true;
    }

    private void mark(Player player) {
        long now = System.currentTimeMillis();
        Long allowed = nextMark.get(player.getUniqueId());
        if (allowed != null && allowed > now) {
            return;
        }
        nextMark.put(player.getUniqueId(), now + 400L);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.0F, 1.6F);
        try {
            markNow(player);
        } catch (RuntimeException exception) {
            getLogger().warning("标记失败: " + exception.getMessage());
            player.sendActionBar(Component.text("标记失败", NamedTextColor.RED));
        }
    }

    private void markNow(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        World world = player.getWorld();
        RayTraceResult blockHit = world.rayTraceBlocks(eye, direction, LOOK_REACH, FluidCollisionMode.NEVER, false);
        RayTraceResult entityHit = world.rayTraceEntities(
                eye,
                direction,
                LOOK_REACH,
                entity -> entity != player && !isMarker(entity)
        );
        double blockDistance = distance(eye, blockHit);
        double entityDistance = distance(eye, entityHit);
        if (entityHit != null && entityHit.getHitEntity() != null && entityDistance <= blockDistance) {
            Entity target = entityHit.getHitEntity();
            spawn(target.getLocation().add(0.0D, target.getHeight() + 0.45D, 0.0D), target);
            player.sendActionBar(Component.text("已标记 " + target.getName(), NamedTextColor.GOLD));
            return;
        }
        if (blockHit != null && blockHit.getHitBlock() != null) {
            Location at = blockHit.getHitPosition().toLocation(world);
            BlockFace face = blockHit.getHitBlockFace();
            if (face != null) {
                at.add(face.getModX() * 0.25D, face.getModY() * 0.25D, face.getModZ() * 0.25D);
            }
            at.add(0.0D, 0.2D, 0.0D);
            spawn(at, null);
            player.sendActionBar(Component.text("已标记方块", NamedTextColor.GOLD));
            return;
        }
        spawn(eye.clone().add(direction.clone().multiply(EMPTY_DISTANCE)), null);
        player.sendActionBar(Component.text("已标记前方 50 格", NamedTextColor.GOLD));
    }

    private void spawn(Location location, Entity follow) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.getChunkAt(location);
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(Component.text("?", NamedTextColor.GOLD, TextDecoration.BOLD));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(170, 18, 36, 72));
            entity.setShadowRadius(0.0F);
            entity.setShadowStrength(0.0F);
            entity.setViewRange(8.0F);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0F, 0.0F, 0.0F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
                    new Vector3f(2.4F, 2.4F, 2.4F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
            ));
            entity.setPersistent(false);
            entity.addScoreboardTag(MARKER_TAG);
        });
        world.playSound(location, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.1F, 1.55F);
        world.spawnParticle(Particle.END_ROD, location, 10, 0.25D, 0.2D, 0.25D, 0.01D);
        pings.add(new Ping(display, follow, LIFETIME_TICKS));
    }

    private void tickPings() {
        Iterator<Ping> iterator = pings.iterator();
        while (iterator.hasNext()) {
            Ping ping = iterator.next();
            ping.ticksLeft -= 10;
            if (ping.ticksLeft <= 0 || !ping.display.isValid()) {
                if (ping.display.isValid()) {
                    ping.display.remove();
                }
                iterator.remove();
                continue;
            }
            if (ping.follow != null && ping.follow.isValid() && ping.follow.getWorld().equals(ping.display.getWorld())) {
                ping.display.teleport(ping.follow.getLocation().add(0.0D, ping.follow.getHeight() + 0.45D, 0.0D));
            }
        }
    }

    private static boolean isMarker(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains(MARKER_TAG);
    }

    private static double distance(Location eye, RayTraceResult hit) {
        if (hit == null || hit.getHitPosition() == null) {
            return Double.MAX_VALUE;
        }
        return eye.toVector().distance(hit.getHitPosition());
    }

    private static final class Ping {
        private final TextDisplay display;
        private final Entity follow;
        private int ticksLeft;

        private Ping(TextDisplay display, Entity follow, int ticksLeft) {
            this.display = display;
            this.follow = follow;
            this.ticksLeft = ticksLeft;
        }
    }
}
