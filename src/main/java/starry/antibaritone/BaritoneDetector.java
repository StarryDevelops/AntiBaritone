package starry.antibaritone;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BaritoneDetector implements Listener {

    private final AntiBaritonePlugin plugin;
    private final Map<UUID, PlayerEvidence> evidence = new HashMap<>();

    public BaritoneDetector(AntiBaritonePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getYaw() == to.getYaw() && from.getPitch() == to.getPitch())) {
            return;
        }

        Player player = event.getPlayer();
        if (ignored(player)) {
            return;
        }

        PlayerEvidence data = data(player);
        long tick = player.getWorld().getFullTime();
        RotationSample sample = data.recordRotation(to.getYaw(), to.getPitch(), tick);
        scorePostBreakSnap(player, data, sample, tick);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        score(event.getPlayer(), event.getBlock(), "damage");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        score(event.getPlayer(), event.getBlock(), "break");
        PlayerEvidence data = data(event.getPlayer());
        long tick = event.getPlayer().getWorld().getFullTime();
        data.markBreak(tick);
        scoreTunnelPattern(event.getPlayer(), event.getBlock(), data, tick);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        score(event.getPlayer(), event.getBlockPlaced(), "place");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        score(event.getPlayer(), event.getClickedBlock(), "use");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        evidence.remove(event.getPlayer().getUniqueId());
    }

    public void clear() {
        evidence.clear();
    }

    public void reset(UUID uuid) {
        evidence.remove(uuid);
    }

    public DetectorSnapshot snapshot(UUID uuid) {
        PlayerEvidence data = evidence.get(uuid);
        return data == null ? DetectorSnapshot.empty() : data.snapshot();
    }

    private PlayerEvidence data(Player player) {
        return evidence.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerEvidence());
    }

    private boolean ignored(Player player) {
        if (!plugin.getConfig().getBoolean("ignore-creative", true)) {
            return false;
        }
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    private void score(Player player, Block block, String action) {
        if (ignored(player)) {
            return;
        }

        World world = player.getWorld();
        long nowTick = world.getFullTime();
        PlayerEvidence data = data(player);
        
        // Decay the score a bit so legitimate players don't build up false flags over time
        data.decay(nowTick, plugin.getConfig().getDouble("score-decay-per-minute", 1.25D));

        RotationFit fit = RotationMath.fit(player.getEyeLocation(), block);
        double preciseAngle = plugin.getConfig().getDouble("precise-angle-degrees", 0.22D);
        double goodAngle = plugin.getConfig().getDouble("good-angle-degrees", 0.45D);
        int snapWindowTicks = plugin.getConfig().getInt("sharp-snap-window-ticks", 4);
        double minYawSnap = plugin.getConfig().getDouble("sharp-snap-min-yaw-degrees", 12.0D);
        double minPitchSnap = plugin.getConfig().getDouble("sharp-snap-min-pitch-degrees", 4.0D);

        boolean precise = fit.angleDegrees() <= preciseAngle;
        boolean good = fit.angleDegrees() <= goodAngle;
        boolean sharpSnap = data.hadSharpSnap(nowTick, snapWindowTicks, minYawSnap, minPitchSnap);
        boolean ore = isOre(block.getType());
        boolean valuableOre = isValuableOre(block.getType());

        double amount = 0.0D;
        if (precise) {
            amount += plugin.getConfig().getDouble("precise-hit-score", 2.35D);
        } else if (good) {
            amount += plugin.getConfig().getDouble("good-hit-score", 1.05D);
        }

        if (sharpSnap && good) {
            amount += plugin.getConfig().getDouble("sharp-snap-score", 1.25D);
        }

        if (good) {
            amount += actionBonus(action);
        }

        if (amount <= 0.0D) {
            data.addBenignSample();
            return;
        }

        // Apply some multipliers based on what they're actually mining
        amount *= plugin.getConfig().getDouble("generic-block-multiplier", 1.0D);
        if (valuableOre) {
            amount *= plugin.getConfig().getDouble("valuable-ore-multiplier", 1.65D);
        } else if (ore) {
            amount *= plugin.getConfig().getDouble("ore-multiplier", 1.35D);
        }

        String reason = String.format(
                Locale.US,
                "%s %s angle=%.3f target=%s snap=%s",
                action,
                block.getType().name().toLowerCase(Locale.ROOT),
                fit.angleDegrees(),
                fit.targetName(),
                sharpSnap
        );
        data.addEvidence(amount, precise, sharpSnap, reason, nowTick);
        maybeAlert(player, data);
    }

    private void scorePostBreakSnap(Player player, PlayerEvidence data, RotationSample sample, long nowTick) {
        if (!data.canScorePostBreakSnap(nowTick, plugin.getConfig().getInt("post-break-snap-window-ticks", 5))) {
            return;
        }

        double minYaw = plugin.getConfig().getDouble("post-break-snap-min-yaw-degrees", 18.0D);
        double minPitch = plugin.getConfig().getDouble("post-break-snap-min-pitch-degrees", 7.0D);
        if (sample.yawDelta() < minYaw && sample.pitchDelta() < minPitch) {
            return;
        }

        data.decay(nowTick, plugin.getConfig().getDouble("score-decay-per-minute", 1.25D));
        double amount = plugin.getConfig().getDouble("post-break-snap-score", 2.75D);
        String reason = String.format(
                Locale.US,
                "post-break-snap yaw=%.2f pitch=%.2f",
                sample.yawDelta(),
                sample.pitchDelta()
        );
        data.addEvidence(amount, false, true, reason, nowTick);
        data.markPostBreakSnapScored();
        maybeAlert(player, data);
    }

    private void scoreTunnelPattern(Player player, Block block, PlayerEvidence data, long nowTick) {
        if (!plugin.getConfig().getBoolean("tunnel-detection-enabled", true)) {
            return;
        }

        Material material = block.getType();
        
        // We only care about solid blocks that aren't ores, since Baritone tries to avoid breaking useless stuff
        if (!material.isSolid() || isOre(material)) {
            return;
        }

        data.addBreakSample(new BreakSample(block.getX(), block.getY(), block.getZ(), nowTick));
        TunnelFit fit = data.bestTunnelFit(
                nowTick,
                plugin.getConfig().getInt("tunnel-window-ticks", 500),
                plugin.getConfig().getInt("tunnel-width-tolerance-blocks", 2),
                plugin.getConfig().getInt("tunnel-height-tolerance-blocks", 2)
        );

        int minBreaks = plugin.getConfig().getInt("tunnel-min-breaks", 10);
        int minDepth = plugin.getConfig().getInt("tunnel-min-depth-blocks", 5);
        if (fit.breaks() < minBreaks || fit.depth() < minDepth) {
            return;
        }

        long cooldownTicks = plugin.getConfig().getLong("tunnel-score-cooldown-ticks", 120L);
        if (!data.canScoreTunnel(nowTick, cooldownTicks)) {
            return;
        }

        data.decay(nowTick, plugin.getConfig().getDouble("score-decay-per-minute", 1.25D));
        double amount = plugin.getConfig().getDouble("tunnel-pattern-score", 4.5D);
        String reason = String.format(
                Locale.US,
                "tunnel-pattern axis=%s breaks=%d depth=%d width=%d height=%d",
                fit.axis(),
                fit.breaks(),
                fit.depth(),
                fit.width(),
                fit.height()
        );
        data.addTunnelEvidence(amount, reason, nowTick);
        data.markTunnelScored(nowTick);
        maybeAlert(player, data);
    }

    private double actionBonus(String action) {
        return switch (action) {
            case "damage" -> plugin.getConfig().getDouble("damage-action-score", 0.35D);
            case "break" -> plugin.getConfig().getDouble("break-action-score", 0.55D);
            case "place" -> plugin.getConfig().getDouble("place-action-score", 0.75D);
            case "use" -> plugin.getConfig().getDouble("use-action-score", 0.45D);
            default -> 0.0D;
        };
    }

    private void maybeAlert(Player player, PlayerEvidence data) {
        int minimumSamples = plugin.getConfig().getInt("minimum-samples-for-alert", 8);
        if (data.samples() < minimumSamples) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        long cooldownMillis = plugin.getConfig().getLong("alert-cooldown-seconds", 20L) * 1000L;
        if (nowMillis - data.lastAlertMillis() < cooldownMillis) {
            return;
        }

        double alertThreshold = plugin.getConfig().getDouble("alert-threshold", 14.0D);
        double severeThreshold = plugin.getConfig().getDouble("severe-threshold", 24.0D);
        if (data.score() < alertThreshold) {
            return;
        }

        data.markAlert(nowMillis);
        String severity = data.score() >= severeThreshold ? "severe" : "suspicious";
        
        // Pass the message and the current score to the plugin so it can decide if it should punish
        plugin.handleDetection(player, String.format(
                Locale.US,
                "%s looks %s for Baritone behavior. score=%.2f samples=%d precise=%d snaps=%d tunnel=%d last=%s",
                player.getName(),
                severity,
                data.score(),
                data.samples(),
                data.preciseHits(),
                data.sharpSnaps(),
                data.tunnelStreaks(),
                data.lastReason()
        ), data.score());
    }

    private static boolean isOre(Material material) {
        String name = material.name();
        return name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
    }

    private static boolean isValuableOre(Material material) {
        String name = material.name();
        return name.equals("DIAMOND_ORE")
                || name.equals("DEEPSLATE_DIAMOND_ORE")
                || name.equals("EMERALD_ORE")
                || name.equals("DEEPSLATE_EMERALD_ORE")
                || name.equals("ANCIENT_DEBRIS");
    }

    private static final class PlayerEvidence {

        private final ArrayDeque<RotationSample> rotations = new ArrayDeque<>();
        private final ArrayDeque<BreakSample> breaks = new ArrayDeque<>();
        private double score;
        private int samples;
        private int preciseHits;
        private int sharpSnaps;
        private int tunnelStreaks;
        private long lastBreakTick = -1L;
        private long lastScoredPostBreakSnapTick = -1L;
        private long lastTunnelScoreTick = -1L;
        private long lastTick;
        private long lastAlertMillis;
        private String lastReason = "none";

        RotationSample recordRotation(float yaw, float pitch, long tick) {
            RotationSample previous = rotations.peekLast();
            double yawDelta = previous == null ? 0.0D : RotationMath.yawDistance(previous.yaw(), yaw);
            double pitchDelta = previous == null ? 0.0D : Math.abs(previous.pitch() - pitch);
            RotationSample sample = new RotationSample(yaw, pitch, yawDelta, pitchDelta, tick);
            rotations.addLast(sample);
            while (rotations.size() > 40) {
                rotations.removeFirst();
            }
            return sample;
        }

        void markBreak(long tick) {
            lastBreakTick = tick;
        }

        boolean canScorePostBreakSnap(long tick, int windowTicks) {
            return lastBreakTick >= 0L
                    && tick > lastBreakTick
                    && tick - lastBreakTick <= windowTicks
                    && lastScoredPostBreakSnapTick != lastBreakTick;
        }

        void markPostBreakSnapScored() {
            lastScoredPostBreakSnapTick = lastBreakTick;
        }

        void addBreakSample(BreakSample sample) {
            breaks.addLast(sample);
            while (breaks.size() > 80) {
                breaks.removeFirst();
            }
        }

        TunnelFit bestTunnelFit(long tick, int windowTicks, int widthTolerance, int heightTolerance) {
            pruneBreaks(tick, windowTicks);
            return better(tunnelFitAlongX(widthTolerance, heightTolerance), tunnelFitAlongZ(widthTolerance, heightTolerance));
        }

        private void pruneBreaks(long tick, int windowTicks) {
            while (!breaks.isEmpty() && tick - breaks.peekFirst().tick() > windowTicks) {
                breaks.removeFirst();
            }
        }

        private TunnelFit tunnelFitAlongX(int widthTolerance, int heightTolerance) {
            if (breaks.isEmpty()) {
                return TunnelFit.empty("x");
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BreakSample sample : breaks) {
                minX = Math.min(minX, sample.x());
                maxX = Math.max(maxX, sample.x());
                minY = Math.min(minY, sample.y());
                maxY = Math.max(maxY, sample.y());
                minZ = Math.min(minZ, sample.z());
                maxZ = Math.max(maxZ, sample.z());
            }
            int width = maxZ - minZ + 1;
            int height = maxY - minY + 1;
            int depth = maxX - minX + 1;
            int count = width <= widthTolerance && height <= heightTolerance + 1 ? breaks.size() : 0;
            return new TunnelFit("x", count, depth, width, height);
        }

        private TunnelFit tunnelFitAlongZ(int widthTolerance, int heightTolerance) {
            if (breaks.isEmpty()) {
                return TunnelFit.empty("z");
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BreakSample sample : breaks) {
                minX = Math.min(minX, sample.x());
                maxX = Math.max(maxX, sample.x());
                minY = Math.min(minY, sample.y());
                maxY = Math.max(maxY, sample.y());
                minZ = Math.min(minZ, sample.z());
                maxZ = Math.max(maxZ, sample.z());
            }
            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            int depth = maxZ - minZ + 1;
            int count = width <= widthTolerance && height <= heightTolerance + 1 ? breaks.size() : 0;
            return new TunnelFit("z", count, depth, width, height);
        }

        private TunnelFit better(TunnelFit a, TunnelFit b) {
            if (a.breaks() != b.breaks()) {
                return a.breaks() > b.breaks() ? a : b;
            }
            return a.depth() >= b.depth() ? a : b;
        }

        boolean canScoreTunnel(long tick, long cooldownTicks) {
            return lastTunnelScoreTick < 0L || tick - lastTunnelScoreTick >= cooldownTicks;
        }

        void markTunnelScored(long tick) {
            lastTunnelScoreTick = tick;
        }

        void decay(long tick, double decayPerMinute) {
            if (lastTick == 0L) {
                lastTick = tick;
                return;
            }
            long elapsed = Math.max(0L, tick - lastTick);
            if (elapsed > 0L) {
                score = Math.max(0.0D, score - (elapsed / 1200.0D) * decayPerMinute);
                lastTick = tick;
            }
        }

        boolean hadSharpSnap(long tick, int windowTicks, double minYaw, double minPitch) {
            for (RotationSample sample : rotations) {
                if (tick - sample.tick() <= windowTicks
                        && (sample.yawDelta() >= minYaw || sample.pitchDelta() >= minPitch)) {
                    return true;
                }
            }
            return false;
        }

        void addEvidence(double amount, boolean precise, boolean sharpSnap, String reason, long tick) {
            samples++;
            score += amount;
            lastReason = reason;
            lastTick = tick;
            if (precise) {
                preciseHits++;
            }
            if (sharpSnap) {
                sharpSnaps++;
            }
        }

        void addTunnelEvidence(double amount, String reason, long tick) {
            samples++;
            score += amount;
            tunnelStreaks++;
            lastReason = reason;
            lastTick = tick;
        }

        void addBenignSample() {
            score = Math.max(0.0D, score - 0.08D);
        }

        DetectorSnapshot snapshot() {
            return new DetectorSnapshot(score, samples, preciseHits, sharpSnaps, tunnelStreaks, lastReason);
        }

        double score() {
            return score;
        }

        int samples() {
            return samples;
        }

        int preciseHits() {
            return preciseHits;
        }

        int sharpSnaps() {
            return sharpSnaps;
        }

        int tunnelStreaks() {
            return tunnelStreaks;
        }

        long lastAlertMillis() {
            return lastAlertMillis;
        }

        String lastReason() {
            return lastReason;
        }

        void markAlert(long nowMillis) {
            lastAlertMillis = nowMillis;
        }
    }

    private record RotationSample(float yaw, float pitch, double yawDelta, double pitchDelta, long tick) {
    }

    private record BreakSample(int x, int y, int z, long tick) {
    }

    private record TunnelFit(String axis, int breaks, int depth, int width, int height) {
        static TunnelFit empty(String axis) {
            return new TunnelFit(axis, 0, 0, 0, 0);
        }
    }

    private record RotationFit(double angleDegrees, String targetName) {
    }

    private static final class RotationMath {

        private static final double[][] TARGET_POINTS = {
                {0.5D, 0.5D, 0.5D},
                {0.5D, 0.0D, 0.5D},
                {0.5D, 1.0D, 0.5D},
                {0.5D, 0.5D, 0.0D},
                {0.5D, 0.5D, 1.0D},
                {0.0D, 0.5D, 0.5D},
                {1.0D, 0.5D, 0.5D}
        };

        private static final String[] TARGET_NAMES = {
                "center", "down", "up", "north", "south", "west", "east"
        };

        static RotationFit fit(Location eye, Block block) {
            Vector look = eye.getDirection().normalize();
            double best = Double.MAX_VALUE;
            String bestName = "unknown";

            for (int i = 0; i < TARGET_POINTS.length; i++) {
                Vector target = new Vector(
                        block.getX() + TARGET_POINTS[i][0],
                        block.getY() + TARGET_POINTS[i][1],
                        block.getZ() + TARGET_POINTS[i][2]
                );
                Vector direction = target.subtract(eye.toVector());
                if (direction.lengthSquared() <= 0.0001D) {
                    continue;
                }
                double angle = angleDegrees(look, direction.normalize());
                if (angle < best) {
                    best = angle;
                    bestName = TARGET_NAMES[i];
                }
            }

            return new RotationFit(best, bestName);
        }

        static double yawDistance(float from, float to) {
            double delta = (to - from) % 360.0D;
            if (delta >= 180.0D) {
                delta -= 360.0D;
            }
            if (delta < -180.0D) {
                delta += 360.0D;
            }
            return Math.abs(delta);
        }

        private static double angleDegrees(Vector a, Vector b) {
            double dot = Math.max(-1.0D, Math.min(1.0D, a.dot(b)));
            return Math.toDegrees(Math.acos(dot));
        }
    }
}
