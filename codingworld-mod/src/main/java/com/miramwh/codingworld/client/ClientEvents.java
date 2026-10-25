package com.miramwh.codingworld.client;

import com.miramwh.codingworld.CodingWorldMod;
import com.miramwh.codingworld.net.ConsolePacket;
import com.miramwh.codingworld.net.KeyPacket;
import com.miramwh.codingworld.net.Network;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class ClientEvents {
    public static final KeyMapping OPEN_EDITOR = new KeyMapping("key.codingworld.editor", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.codingworld");

    private ClientEvents() {}

    @Mod.EventBusSubscriber(modid = CodingWorldMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) {
            e.register(OPEN_EDITOR);
        }

        @SubscribeEvent
        public static void overlays(RegisterGuiOverlaysEvent e) {
            e.registerAboveAll("console", (gui, g, partial, w, h) -> renderHud(g, w, h));
        }
    }

    @Mod.EventBusSubscriber(modid = CodingWorldMod.MODID, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            while (OPEN_EDITOR.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new CodeEditorScreen());
            }
            if (ClientState.pendingOpen && mc.screen == null && mc.level != null) {
                ClientState.pendingOpen = false;
                mc.setScreen(new CodeEditorScreen());
            }
        }

        @SubscribeEvent
        public static void key(InputEvent.Key e) {
            Minecraft mc = Minecraft.getInstance();
            if (!ClientState.wantsKeys || mc.screen != null || mc.player == null || e.getAction() != GLFW.GLFW_PRESS) return;
            if (OPEN_EDITOR.matches(e.getKey(), e.getScanCode())) return;
            String name = InputConstants.getKey(e.getKey(), e.getScanCode()).getName();
            name = name.replace("key.keyboard.", "");
            if (name.length() <= 32) Network.toServer(new KeyPacket(name));
        }

        @SubscribeEvent
        public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut e) {
            ClientState.reset();
        }
    }

    /** Shows the last console lines while the editor is closed. */
    private static void renderHud(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CodeEditorScreen || mc.options.hideGui || mc.player == null) return;
        Font font = mc.font;
        long now = System.currentTimeMillis();
        List<ClientState.Line> all = ClientState.console;
        int shown = 0;
        int y = 4;
        int maxW = Math.min(260, w / 3);
        for (int i = Math.max(0, all.size() - 6); i < all.size(); i++) {
            ClientState.Line l = all.get(i);
            long age = now - l.time();
            if (age > 12_000) continue;
            int alpha = age > 10_000 ? (int) (255 * (12_000 - age) / 2000) : 255;
            if (alpha < 8) continue;
            int col = switch (l.type()) {
                case ConsolePacket.ERROR -> 0xFF6B6B;
                case ConsolePacket.SYSTEM -> 0x7FB8FF;
                case ConsolePacket.WARN -> 0xFFB86B;
                default -> 0xEAEAEA;
            };
            String t = font.plainSubstrByWidth(l.text(), maxW);
            g.fill(w - maxW - 8, y - 2, w - 2, y + 9, (alpha * 0x88 / 255) << 24);
            g.drawString(font, t, w - maxW - 4, y, (alpha << 24) | col, false);
            y += 11;
            shown++;
        }
        if (ClientState.inCodingWorld) {
            String hint = (ClientState.running ? "● код работает · " : "") + "K — редактор кода";
            g.drawString(font, hint, 4, h - 12, ClientState.running ? 0xFF55FF55 : 0xFFAAAAAA, true);
        }
    }
}
