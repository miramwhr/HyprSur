package com.miramwh.codingworld.content;

import com.miramwh.codingworld.CodingWorldMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The mod's own block and item: the redstone signal block and the "Code Teach" book. */
public final class ModContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, CodingWorldMod.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, CodingWorldMod.MODID);

    public static final RegistryObject<SignalBlock> SIGNAL = BLOCKS.register("signal", SignalBlock::new);
    public static final RegistryObject<Item> CODE_TEACH = ITEMS.register("code_teach", CodeTeachItem::new);

    private ModContent() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        bus.addListener(ModContent::creativeTab);
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(CODE_TEACH);
    }
}
