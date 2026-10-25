package com.miramwh.codingworld.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Sent when a player joins a Coding world: opens the editor. */
public final class OpenEditorPacket {
    public final boolean firstVisit;

    public OpenEditorPacket(boolean firstVisit) {
        this.firstVisit = firstVisit;
    }

    OpenEditorPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(firstVisit);
    }

    void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.miramwh.codingworld.client.ClientState.onOpenEditor(this));
    }
}
