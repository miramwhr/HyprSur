package com.miramwh.codingworld.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Lines for the console panel. */
public final class ConsolePacket {
    public static final int NORMAL = 0, ERROR = 1, SYSTEM = 2, WARN = 3, CLEAR = 4;

    public record Line(String text, int type, int line) {}

    public final List<Line> lines;

    public ConsolePacket(List<Line> lines) {
        this.lines = lines;
    }

    ConsolePacket(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lines.add(new Line(buf.readUtf(8192), buf.readByte(), buf.readVarInt()));
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(lines.size());
        for (Line l : lines) {
            buf.writeUtf(l.text.length() > 2048 ? l.text.substring(0, 2048) : l.text, 8192);
            buf.writeByte(l.type);
            buf.writeVarInt(l.line);
        }
    }

    void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.miramwh.codingworld.client.ClientState.onConsole(this));
    }
}
