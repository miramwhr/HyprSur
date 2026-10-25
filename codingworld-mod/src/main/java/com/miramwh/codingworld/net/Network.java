package com.miramwh.codingworld.net;

import com.miramwh.codingworld.CodingWorldMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Network {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CodingWorldMod.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private Network() {}

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(RunPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RunPacket::encode).decoder(RunPacket::new).consumerMainThread(RunPacket::handle).add();
        CHANNEL.messageBuilder(StopPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StopPacket::encode).decoder(StopPacket::new).consumerMainThread(StopPacket::handle).add();
        CHANNEL.messageBuilder(KeyPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(KeyPacket::encode).decoder(KeyPacket::new).consumerMainThread(KeyPacket::handle).add();
        CHANNEL.messageBuilder(ConsolePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ConsolePacket::encode).decoder(ConsolePacket::new).consumerMainThread(ConsolePacket::handle).add();
        CHANNEL.messageBuilder(StatusPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatusPacket::encode).decoder(StatusPacket::new).consumerMainThread(StatusPacket::handle).add();
        CHANNEL.messageBuilder(OpenEditorPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenEditorPacket::encode).decoder(OpenEditorPacket::new).consumerMainThread(OpenEditorPacket::handle).add();
    }

    public static void toPlayer(ServerPlayer p, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg);
    }

    public static void toServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }
}
