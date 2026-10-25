package com.miramwh.codingworld.net;

import com.miramwh.codingworld.server.CodingServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** One chunk of the script source; the server starts the script once the last chunk arrives. */
public final class RunPacket {
    public static final int CHUNK = 8000;
    final int part, total;
    final String text;

    public RunPacket(int part, int total, String text) {
        this.part = part;
        this.total = total;
        this.text = text;
    }

    RunPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readUtf(CHUNK));
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(part);
        buf.writeVarInt(total);
        buf.writeUtf(text, CHUNK);
    }

    void handle(Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getSender() != null) CodingServer.receiveChunk(ctx.get().getSender(), part, total, text);
    }
}
