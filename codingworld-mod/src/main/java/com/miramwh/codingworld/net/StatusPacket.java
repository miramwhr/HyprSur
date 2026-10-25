package com.miramwh.codingworld.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Whether the player's script is running and whether it listens to key presses. */
public final class StatusPacket {
    public final boolean running, wantsKeys;

    public StatusPacket(boolean running, boolean wantsKeys) {
        this.running = running;
        this.wantsKeys = wantsKeys;
    }

    StatusPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readBoolean());
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(running);
        buf.writeBoolean(wantsKeys);
    }

    void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.miramwh.codingworld.client.ClientState.onStatus(this));
    }
}
