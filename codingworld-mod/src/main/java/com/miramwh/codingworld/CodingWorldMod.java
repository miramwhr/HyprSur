package com.miramwh.codingworld;

import com.miramwh.codingworld.net.Network;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(CodingWorldMod.MODID)
public class CodingWorldMod {
    public static final String MODID = "codingworld";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CodingWorldMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
    }

    private void setup(FMLCommonSetupEvent e) {
        e.enqueueWork(Network::register);
    }
}
