package io.github.gameai.forgeagent;

import com.mojang.logging.LogUtils;
import io.github.gameai.forgeagent.client.ClientAgentController;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(GameAiForgeAgent.MOD_ID)
public class GameAiForgeAgent {
    public static final String MOD_ID = "gameai_forge_agent";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GameAiForgeAgent() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                FMLJavaModLoadingContext.get().getModEventBus().addListener(GameAiForgeAgent::onClientSetup)
        );
    }

    private static void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Game AI Forge Agent received FMLClientSetupEvent.");
        event.enqueueWork(ClientAgentController::bootstrap);
    }
}
