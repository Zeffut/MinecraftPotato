package com.potatomc.entity;

import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

/**
 * Decides whether a {@link MobEntity}'s AI tick can be skipped because no
 * server player is within {@link #SKIP_DISTANCE_SQ} blocks (squared). Ridden
 * or riding mobs are never skipped — they participate in vehicles a player
 * directly observes.
 */
public final class DistantEntityTracker {
    public static final int SKIP_DISTANCE = 32;
    public static final double SKIP_DISTANCE_SQ = (double) SKIP_DISTANCE * SKIP_DISTANCE;

    private DistantEntityTracker() {}

    /**
     * Always allow mobTick to run on these ticks (mod 4 == 0) so despawn
     * checks, age, and slow-tick housekeeping still fire ~5 Hz instead of
     * 20 Hz. Without this, hostile mobs would never despawn far from players
     * and the entity count would grow unbounded.
     */
    private static final int FORCE_RUN_INTERVAL = 4;

    public static boolean shouldSkipAITick(MobEntity entity) {
        if (!EntityTickSkipGuard.isActive()) return false;
        if (entity.hasPassengers() || entity.hasVehicle()) return false;
        if (entity.age % FORCE_RUN_INTERVAL == 0) return false;
        World world = entity.getEntityWorld();
        if (!(world instanceof ServerWorld sw)) return false;
        double sx = entity.getX();
        double sy = entity.getY();
        double sz = entity.getZ();
        for (ServerPlayerEntity player : sw.getPlayers()) {
            double dx = player.getX() - sx;
            double dy = player.getY() - sy;
            double dz = player.getZ() - sz;
            if (dx * dx + dy * dy + dz * dz < SKIP_DISTANCE_SQ) return false;
        }
        return true;
    }
}
