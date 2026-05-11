package com.potatomc.debug.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.potatomc.PotatoMC;
import com.potatomc.debug.DifferentialValidator;
import com.potatomc.lighting.CompatGuard;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class PotatoMCCommand {

    private PotatoMCCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("potatomc")
            .then(CommandManager.literal("status").executes(ctx -> {
                ServerCommandSource src = ctx.getSource();
                src.sendFeedback(() -> Text.literal(
                    "PotatoMC — moteur lumière: " + (CompatGuard.isActive() ? "ACTIF" : "DÉSACTIVÉ (compat)")
                        + " | sections suivies: " + PotatoMC.LIGHT_ENGINE.trackedSectionsCount()
                ), false);
                return 1;
            }))
            .then(CommandManager.literal("validate")
                .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 16))
                    .executes(ctx -> {
                        int radius = IntegerArgumentType.getInteger(ctx, "radius");
                        ServerCommandSource src = ctx.getSource();
                        DifferentialValidator.Report r = DifferentialValidator.runAround(
                            src.getWorld(), src.getPosition(), radius);
                        src.sendFeedback(() -> Text.literal(String.format(
                            "Validation: %d blocs comparés, %d diffs (max delta %d)",
                            r.totalBlocks(), r.diffCount(), r.maxDelta())), false);
                        return 1;
                    })))
        );
        PotatoMC.LOGGER.info("[PotatoMC] /potatomc enregistré");
    }
}
