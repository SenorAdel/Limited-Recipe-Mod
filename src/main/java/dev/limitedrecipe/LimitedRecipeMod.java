package dev.limitedrecipe;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(LimitedRecipeMod.MOD_ID)
public final class LimitedRecipeMod {
    public static final String MOD_ID = "limitedrecipe";

    public LimitedRecipeMod(IEventBus modEventBus) {
        modEventBus.addListener(NetworkMessages::register);
        NeoForge.EVENT_BUS.addListener(RecipeAccessCommand::register);
        NeoForge.EVENT_BUS.addListener(PermissionStore::onServerStarted);
        NeoForge.EVENT_BUS.addListener(Networking::onPlayerLoggedIn);
    }
}
