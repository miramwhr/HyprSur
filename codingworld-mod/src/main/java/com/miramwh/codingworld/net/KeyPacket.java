package com.miramwh.codingworld.net;

import com.miramwh.codingworld.server.CodingServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class KeyPacket {
    final String key;

    public KeyPacket(String key) {
        this.key = key;
    }

    KeyPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(32));
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeUtf(key, 32);
    }

    void handle(Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getSender() != null) CodingServer.onKey(ctx.get().getSender(), key);
    }
}
