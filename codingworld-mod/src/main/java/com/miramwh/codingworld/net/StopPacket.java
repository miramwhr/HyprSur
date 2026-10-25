package com.miramwh.codingworld.net;

import com.miramwh.codingworld.server.CodingServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class StopPacket {
    public StopPacket() {}

    StopPacket(FriendlyByteBuf buf) {}

    void encode(FriendlyByteBuf buf) {}

    void handle(Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getSender() != null) CodingServer.stop(ctx.get().getSender(), true);
    }
}
