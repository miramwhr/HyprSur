package com.miramwh.codingworld.server;

import com.miramwh.codingworld.CodingWorldMod;
import com.miramwh.codingworld.net.ConsolePacket;
import com.miramwh.codingworld.net.Network;
import com.miramwh.codingworld.net.OpenEditorPacket;
import com.miramwh.codingworld.net.StatusPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Starts and stops scripts and forwards game events to them. */
@Mod.EventBusSubscriber(modid = CodingWorldMod.MODID)
public final class CodingServer {
    public static final int MAX_CODE = 200_000;
    private static final Map<UUID, ScriptRunner> RUNNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, String[]> CHUNKS = new ConcurrentHashMap<>();

    private CodingServer() {}

    public static boolean canRun(ServerPlayer p) {
        MinecraftServer s = p.getServer();
        return s != null && (s.isSingleplayerOwner(p.getGameProfile()) || p.hasPermissions(2));
    }

    private static void console(ServerPlayer p, String text, int type) {
        Network.toPlayer(p, new ConsolePacket(List.of(new ConsolePacket.Line(text, type, 0))));
    }

    public static void receiveChunk(ServerPlayer p, int part, int total, String text) {
        if (!canRun(p)) {
            console(p, "Запускать код на этом сервере может только оператор (OP).", ConsolePacket.ERROR);
            return;
        }
        if (total < 1 || total > MAX_CODE / 1000 || part < 0 || part >= total) return;
        String[] parts = part == 0 ? new String[total] : CHUNKS.get(p.getUUID());
        if (parts == null || parts.length != total) return;
        parts[part] = text;
        CHUNKS.put(p.getUUID(), parts);
        if (part != total - 1) return;
        CHUNKS.remove(p.getUUID());
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s == null) return;
            sb.append(s);
        }
        start(p, sb.toString());
    }

    static void start(ServerPlayer p, String code) {
        ScriptRunner old = RUNNERS.remove(p.getUUID());
        if (old != null) old.stop();
        ScriptRunner r = new ScriptRunner(p.getServer(), p, code);
        RUNNERS.put(p.getUUID(), r);
        Network.toPlayer(p, new StatusPacket(true, false));
        r.start();
    }

    public static void stop(ServerPlayer p, boolean byUser) {
        ScriptRunner r = RUNNERS.get(p.getUUID());
        if (r != null) r.stop();
        else if (byUser) Network.toPlayer(p, new StatusPacket(false, false));
    }

    static void onFinished(ScriptRunner r) {
        RUNNERS.remove(r.playerId, r);
        r.server.execute(() -> {
            r.flushOutput();
            r.flushOutput();
            if (RUNNERS.containsKey(r.playerId)) return;
            ServerPlayer p = r.server.getPlayerList().getPlayer(r.playerId);
            if (p != null) Network.toPlayer(p, new StatusPacket(false, false));
        });
    }

    static void sendStatus(ScriptRunner r, boolean running) {
        r.server.execute(() -> {
            ServerPlayer p = r.server.getPlayerList().getPlayer(r.playerId);
            if (p != null && RUNNERS.get(r.playerId) == r) Network.toPlayer(p, new StatusPacket(running, r.wants("key")));
        });
    }

    public static void onKey(ServerPlayer p, String key) {
        ScriptRunner r = RUNNERS.get(p.getUUID());
        if (r != null) r.offer("key", key);
    }

    // ---------- coding world ----------
    public static boolean isCodingWorld(MinecraftServer s) {
        ChunkGenerator g = s.overworld().getChunkSource().getGenerator();
        if (!(g instanceof FlatLevelSource f)) return false;
        List<FlatLayerInfo> layers = f.settings().getLayersInfo();
        if (layers.size() != 4) return false;
        return layers.get(0).getBlockState().is(Blocks.BEDROCK) && layers.get(1).getBlockState().is(Blocks.STONE)
                && layers.get(1).getHeight() == 59 && layers.get(3).getBlockState().is(Blocks.GRASS_BLOCK);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.getServer() == null) return;
        MinecraftServer s = p.getServer();
        if (!isCodingWorld(s)) return;
        CompoundTag data = p.getPersistentData();
        boolean first = !data.getBoolean("codingworld_setup");
        if (first) {
            data.putBoolean("codingworld_setup", true);
            p.setGameMode(GameType.SPECTATOR);
            ServerLevel lv = s.overworld();
            p.teleportTo(lv, 8.5, 16, 36.5, 180f, 25f);
            GameRules rules = s.getGameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, s);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, s);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, s);
            lv.setDayTime(6000);
            lv.setDefaultSpawnPos(new BlockPos(0, 0, 0), 0);
        }
        Network.toPlayer(p, new OpenEditorPacket(first));
        console(p, first ? "Добро пожаловать в Кодинг-мир! Напишите код слева и нажмите ▶ Запуск (или Ctrl+Enter)."
                : "Кодинг-мир: K — открыть редактор, Esc — закрыть.", ConsolePacket.SYSTEM);
    }

    // ---------- events ----------
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || RUNNERS.isEmpty()) return;
        for (ScriptRunner r : RUNNERS.values()) r.onServerTick();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        ScriptRunner r = RUNNERS.remove(e.getEntity().getUUID());
        if (r != null) r.stop();
        CHUNKS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        for (ScriptRunner r : RUNNERS.values()) r.stop();
        RUNNERS.clear();
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent e) {
        ScriptRunner r = RUNNERS.get(e.getPlayer().getUUID());
        if (r != null) r.offer("chat", e.getRawText());
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        ScriptRunner r = RUNNERS.get(p.getUUID());
        BlockPos b = e.getPos();
        if (r != null) r.offer("block_break", (long) b.getX(), (long) b.getY(), (long) b.getZ(), WorldApi.blockName(e.getState()));
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        ScriptRunner r = RUNNERS.get(p.getUUID());
        BlockPos b = e.getPos();
        if (r != null) r.offer("block_place", (long) b.getX(), (long) b.getY(), (long) b.getZ(), WorldApi.blockName(e.getPlacedBlock()));
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        Entity en = e.getEntity();
        if (en.level().isClientSide() || en instanceof Player || RUNNERS.isEmpty()) return;
        String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getPath();
        BlockPos b = en.blockPosition();
        for (ScriptRunner r : RUNNERS.values()) {
            ServerPlayer p = r.server.getPlayerList().getPlayer(r.playerId);
            if (p != null && p.level() == en.level()) r.offer("mob_death", type, (long) b.getX(), (long) b.getY(), (long) b.getZ());
        }
    }
}
