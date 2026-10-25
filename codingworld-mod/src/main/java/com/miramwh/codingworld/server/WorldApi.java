package com.miramwh.codingworld.server;

import com.miramwh.codingworld.api.ApiDocs;
import com.miramwh.codingworld.py.Interpreter;
import com.miramwh.codingworld.py.Py;
import com.miramwh.codingworld.py.PyError;
import com.miramwh.codingworld.py.Values.PyDict;
import com.miramwh.codingworld.py.Values.PyList;
import com.miramwh.codingworld.py.Values.PyTuple;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Implementation of every world function and live variable listed in {@link ApiDocs}. */
final class WorldApi {
    interface Impl {
        Object call(Args a);
    }

    static final int MAX_BLOCKS = 1_000_000;
    private static final Map<String, BlockState> BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> BLOCK_ALIASES = Map.ofEntries(
            Map.entry("hedge", "oak_leaves"), Map.entry("wood", "oak_planks"), Map.entry("planks", "oak_planks"),
            Map.entry("log", "oak_log"), Map.entry("leaves", "oak_leaves"), Map.entry("grass", "grass_block"),
            Map.entry("wool", "white_wool"), Map.entry("concrete", "white_concrete"), Map.entry("brick", "bricks"),
            Map.entry("door", "oak_door"), Map.entry("lava_block", "lava"), Map.entry("water_block", "water"));

    private final ScriptRunner r;
    private final Map<String, Impl> impls = new HashMap<>();
    private List<Object[]> clipboard = new ArrayList<>(); // {dx, dy, dz, BlockState}

    WorldApi(ScriptRunner runner, Interpreter in) {
        this.r = runner;
        registerBlocks();
        registerShapes();
        registerMobs();
        registerPlayer();
        registerWorld();
        registerEffects();
        registerMessages(in);
        registerTime(in);
        registerUtil(in);
        registerRedstone();
        registerCrafts();
        for (ApiDocs.Fn doc : ApiDocs.FUNCTIONS) {
            if (doc.name().equals("print")) continue;
            Impl impl = impls.get(doc.name());
            if (impl == null) throw new IllegalStateException("No implementation for " + doc.name());
            List<String[]> params = Args.parseParams(doc.params());
            in.define(doc.name(), (it, a, kw) -> impl.call(Args.bind(doc, params, a, kw)));
        }
        ApiDocs.Fn craftDoc = ApiDocs.BY_NAME.get("Craft");
        List<String[]> craftParams = Args.parseParams(craftDoc.params());
        in.define("craft", (it, a, kw) -> impls.get("Craft").call(Args.bind(craftDoc, craftParams, a, kw)));
    }

    private void def(String name, Impl impl) {
        impls.put(name, impl);
    }

    // =====================================================================
    // name lookups (safe from the script thread: registries are frozen)
    // =====================================================================
    private static ResourceLocation rl(String name, String what) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (n.startsWith("minecraft:")) n = n.substring(10);
        ResourceLocation id = ResourceLocation.tryParse(n.contains(":") ? n : "minecraft:" + n);
        if (id == null) throw new PyError("WorldError", "неправильное название " + what + ": " + Py.repr(name));
        return id;
    }

    private static String suggest(String name, Set<ResourceLocation> keys) {
        String n = name.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (ResourceLocation k : keys) {
            String p = k.getPath();
            int d = Interpreter.levenshtein(n, p);
            if (p.contains(n) && n.length() >= 3) d = Math.min(d, 2);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best != null && bestD <= Math.max(2, n.length() / 3)) return ". Может быть, \"" + best + "\"?";
        return "";
    }

    static BlockState block(String name) {
        BlockState cached = BLOCK_CACHE.get(name);
        if (cached != null) return cached;
        String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String base = n.contains("[") ? n.substring(0, n.indexOf('[')) : n;
        String rest = n.contains("[") ? n.substring(n.indexOf('[')) : "";
        if (BLOCK_ALIASES.containsKey(base)) base = BLOCK_ALIASES.get(base);
        ResourceLocation id = rl(base, "блока");
        if (!BuiltInRegistries.BLOCK.containsKey(id))
            throw new PyError("WorldError", "неизвестный блок " + Py.repr(name) + suggest(base, BuiltInRegistries.BLOCK.keySet()));
        BlockState st;
        if (rest.isEmpty()) {
            st = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        } else {
            try {
                st = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), id + rest, false).blockState();
            } catch (CommandSyntaxException e) {
                throw new PyError("WorldError", "неправильное состояние блока " + Py.repr(name) + ": " + e.getRawMessage().getString());
            }
        }
        if (st.getBlock() instanceof LeavesBlock && !rest.contains("persistent")) st = st.setValue(LeavesBlock.PERSISTENT, true);
        if (BLOCK_CACHE.size() < 5000) BLOCK_CACHE.put(name, st);
        return st;
    }

    static String blockName(BlockState st) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    private static Item item(String name) {
        ResourceLocation id = rl(name, "предмета");
        if (!BuiltInRegistries.ITEM.containsKey(id))
            throw new PyError("WorldError", "неизвестный предмет " + Py.repr(name) + suggest(name, BuiltInRegistries.ITEM.keySet()));
        return BuiltInRegistries.ITEM.get(id);
    }

    private static EntityType<?> entityType(String name) {
        ResourceLocation id = rl(name, "моба");
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id))
            throw new PyError("WorldError", "неизвестный моб " + Py.repr(name) + suggest(name, BuiltInRegistries.ENTITY_TYPE.keySet()));
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (t == EntityType.PLAYER) throw new PyError("WorldError", "игрока призвать нельзя");
        return t;
    }

    private static String typeName(Entity e) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    private static MobEffect effect(String name) {
        ResourceLocation id = rl(name, "эффекта");
        if (!BuiltInRegistries.MOB_EFFECT.containsKey(id))
            throw new PyError("WorldError", "неизвестный эффект " + Py.repr(name) + suggest(name, BuiltInRegistries.MOB_EFFECT.keySet()));
        return BuiltInRegistries.MOB_EFFECT.get(id);
    }

    private static ParticleOptions particle(String name) {
        ResourceLocation id = rl(name, "частиц");
        if (!BuiltInRegistries.PARTICLE_TYPE.containsKey(id))
            throw new PyError("WorldError", "неизвестные частицы " + Py.repr(name) + suggest(name, BuiltInRegistries.PARTICLE_TYPE.keySet()));
        ParticleType<?> t = BuiltInRegistries.PARTICLE_TYPE.get(id);
        if (t instanceof SimpleParticleType s) return s;
        throw new PyError("WorldError", "частицы " + Py.repr(name) + " требуют настроек; используйте Command(\"particle ...\")");
    }

    private static SoundEvent sound(String name) {
        ResourceLocation id = rl(name, "звука");
        if (!BuiltInRegistries.SOUND_EVENT.containsKey(id))
            throw new PyError("WorldError", "неизвестный звук " + Py.repr(name) + suggest(name, BuiltInRegistries.SOUND_EVENT.keySet()));
        return BuiltInRegistries.SOUND_EVENT.get(id);
    }

    private static BlockPos pos(Args a, int i) {
        return new BlockPos(a.i(i), a.i(i + 1), a.i(i + 2));
    }

    private static void checkVolume(long n) {
        if (n > MAX_BLOCKS) throw new PyError("WorldError", "слишком большая область: " + n + " блоков (максимум " + MAX_BLOCKS + " за раз)");
    }

    private ServerLevel level() {
        return r.player().serverLevel();
    }

    private Entity entity(Args a, int i) {
        int id = a.i(i);
        Entity e = level().getEntity(id);
        if (e == null || !e.isAlive() || e instanceof Player)
            throw new PyError("WorldError", "моб с номером " + id + " не найден (может, он уже исчез?)");
        return e;
    }

    private LivingEntity living(Args a, int i) {
        Entity e = entity(a, i);
        if (e instanceof LivingEntity le) return le;
        throw new PyError("WorldError", "сущность " + typeName(e) + " — не живое существо");
    }

    private Mob mob(Args a, int i) {
        Entity e = entity(a, i);
        if (e instanceof Mob m) return m;
        throw new PyError("WorldError", "сущность " + typeName(e) + " — не моб");
    }

    // =====================================================================
    // bulk block placement
    // =====================================================================
    private int setAll(List<BlockPos> positions, BlockState st) {
        checkVolume(positions.size());
        return r.sync(() -> {
            ServerLevel level = level();
            int n = 0;
            for (BlockPos p : positions) {
                if (level.isOutsideBuildHeight(p)) continue;
                if (level.setBlock(p, st, Block.UPDATE_CLIENTS)) n++;
            }
            return n;
        });
    }

    private int fillBox(int x1, int y1, int z1, int x2, int y2, int z2, BlockState st, Predicate<int[]> where) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
        int ay = Math.min(y1, y2), by = Math.max(y1, y2);
        int az = Math.min(z1, z2), bz = Math.max(z1, z2);
        checkVolume((long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1));
        return r.sync(() -> {
            ServerLevel level = level();
            int ly = Math.max(ay, level.getMinBuildHeight()), hy = Math.min(by, level.getMaxBuildHeight() - 1);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int n = 0;
            int[] c = new int[3];
            for (int x = ax; x <= bx; x++)
                for (int y = ly; y <= hy; y++)
                    for (int z = az; z <= bz; z++) {
                        c[0] = x;
                        c[1] = y;
                        c[2] = z;
                        if (where != null && !where.test(c)) continue;
                        p.set(x, y, z);
                        if (level.setBlock(p, st, Block.UPDATE_CLIENTS)) n++;
                    }
            return n;
        });
    }

    // =====================================================================
    // blocks
    // =====================================================================
    private void registerBlocks() {
        def("SetBlock", a -> {
            BlockPos p = pos(a, 0);
            BlockState st = block(a.s(3));
            r.async(() -> {
                ServerLevel level = level();
                if (!level.isOutsideBuildHeight(p)) level.setBlock(p, st, Block.UPDATE_ALL);
            });
            return null;
        });
        def("GetBlock", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> blockName(level().getBlockState(p)));
        });
        def("IsBlock", a -> {
            BlockPos p = pos(a, 0);
            String want = a.s(3);
            BlockState st = block(want);
            boolean exact = want.contains("[");
            return r.sync(() -> {
                BlockState cur = level().getBlockState(p);
                return exact ? cur == st : cur.getBlock() == st.getBlock();
            });
        });
        def("IsAir", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> level().getBlockState(p).isAir());
        });
        def("BreakBlock", a -> {
            BlockPos p = pos(a, 0);
            boolean drop = a.b(3);
            return r.sync(() -> level().destroyBlock(p, drop));
        });
        def("Fill", a -> (long) fillBox(a.i(0), a.i(1), a.i(2), a.i(3), a.i(4), a.i(5), block(a.s(6)), null));
        def("FillHollow", a -> {
            int x1 = Math.min(a.i(0), a.i(3)), x2 = Math.max(a.i(0), a.i(3));
            int y1 = Math.min(a.i(1), a.i(4)), y2 = Math.max(a.i(1), a.i(4));
            int z1 = Math.min(a.i(2), a.i(5)), z2 = Math.max(a.i(2), a.i(5));
            BlockState st = block(a.s(6));
            BlockState air = Blocks.AIR.defaultBlockState();
            fillBox(x1, y1, z1, x2, y2, z2, air, c -> c[0] > x1 && c[0] < x2 && c[1] > y1 && c[1] < y2 && c[2] > z1 && c[2] < z2);
            return (long) fillBox(x1, y1, z1, x2, y2, z2, st, c -> c[0] == x1 || c[0] == x2 || c[1] == y1 || c[1] == y2 || c[2] == z1 || c[2] == z2);
        });
        def("Walls", a -> {
            int x1 = Math.min(a.i(0), a.i(3)), x2 = Math.max(a.i(0), a.i(3));
            int z1 = Math.min(a.i(2), a.i(5)), z2 = Math.max(a.i(2), a.i(5));
            return (long) fillBox(x1, a.i(1), z1, x2, a.i(4), z2, block(a.s(6)), c -> c[0] == x1 || c[0] == x2 || c[2] == z1 || c[2] == z2);
        });
        def("Floor", a -> (long) fillBox(a.i(0), a.i(4), a.i(1), a.i(2), a.i(4), a.i(3), block(a.s(5)), null));
        def("Replace", a -> {
            int x1 = Math.min(a.i(0), a.i(3)), x2 = Math.max(a.i(0), a.i(3));
            int y1 = Math.min(a.i(1), a.i(4)), y2 = Math.max(a.i(1), a.i(4));
            int z1 = Math.min(a.i(2), a.i(5)), z2 = Math.max(a.i(2), a.i(5));
            checkVolume((long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1));
            Block from = block(a.s(6)).getBlock();
            BlockState to = block(a.s(7));
            return r.sync(() -> {
                ServerLevel level = level();
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                long n = 0;
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) {
                            p.set(x, y, z);
                            if (level.getBlockState(p).getBlock() == from && level.setBlock(p, to, Block.UPDATE_CLIENTS)) n++;
                        }
                return n;
            });
        });
        def("Clear", a -> (long) fillBox(a.i(0), a.i(1), a.i(2), a.i(3), a.i(4), a.i(5), Blocks.AIR.defaultBlockState(), null));
        def("CountBlocks", a -> {
            int x1 = Math.min(a.i(0), a.i(3)), x2 = Math.max(a.i(0), a.i(3));
            int y1 = Math.min(a.i(1), a.i(4)), y2 = Math.max(a.i(1), a.i(4));
            int z1 = Math.min(a.i(2), a.i(5)), z2 = Math.max(a.i(2), a.i(5));
            checkVolume((long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1));
            Block b = block(a.s(6)).getBlock();
            return r.sync(() -> {
                ServerLevel level = level();
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                long n = 0;
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) if (level.getBlockState(p.set(x, y, z)).getBlock() == b) n++;
                return n;
            });
        });
        def("TopY", a -> {
            int x = a.i(0), z = a.i(1);
            return r.sync(() -> (long) level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
        });
        def("Line", a -> {
            int x1 = a.i(0), y1 = a.i(1), z1 = a.i(2), x2 = a.i(3), y2 = a.i(4), z2 = a.i(5);
            BlockState st = block(a.s(6));
            int steps = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
            checkVolume(steps);
            List<BlockPos> list = new ArrayList<>();
            for (int i = 0; i <= steps; i++) {
                double t = steps == 0 ? 0 : (double) i / steps;
                list.add(new BlockPos((int) Math.round(x1 + (x2 - x1) * t), (int) Math.round(y1 + (y2 - y1) * t), (int) Math.round(z1 + (z2 - z1) * t)));
            }
            return (long) setAll(list, st);
        });
        def("Copy", a -> {
            int x1 = Math.min(a.i(0), a.i(3)), x2 = Math.max(a.i(0), a.i(3));
            int y1 = Math.min(a.i(1), a.i(4)), y2 = Math.max(a.i(1), a.i(4));
            int z1 = Math.min(a.i(2), a.i(5)), z2 = Math.max(a.i(2), a.i(5));
            checkVolume((long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1));
            clipboard = r.sync(() -> {
                ServerLevel level = level();
                List<Object[]> out = new ArrayList<>();
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) out.add(new Object[]{x - x1, y - y1, z - z1, level.getBlockState(new BlockPos(x, y, z))});
                return out;
            });
            return (long) clipboard.size();
        });
        def("Paste", a -> {
            int x = a.i(0), y = a.i(1), z = a.i(2);
            List<Object[]> clip = clipboard;
            if (clip.isEmpty()) throw new PyError("WorldError", "буфер пуст — сначала вызовите Copy(...)");
            return r.sync(() -> {
                ServerLevel level = level();
                long n = 0;
                for (Object[] e : clip) {
                    BlockPos p = new BlockPos(x + (int) e[0], y + (int) e[1], z + (int) e[2]);
                    if (!level.isOutsideBuildHeight(p) && level.setBlock(p, (BlockState) e[3], Block.UPDATE_CLIENTS)) n++;
                }
                return n;
            });
        });
    }

    // =====================================================================
    // shapes
    // =====================================================================
    interface Inside {
        boolean test(int dx, int dy, int dz);
    }

    private static List<BlockPos> shape(int cx, int cy, int cz, int rx, int ry0, int ry1, int rz, Inside inside, boolean hollow) {
        long vol = (long) (2 * rx + 1) * (ry1 - ry0 + 1) * (2 * rz + 1);
        checkVolume(vol);
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -rx; dx <= rx; dx++)
            for (int dy = ry0; dy <= ry1; dy++)
                for (int dz = -rz; dz <= rz; dz++) {
                    if (!inside.test(dx, dy, dz)) continue;
                    if (hollow && inside.test(dx + 1, dy, dz) && inside.test(dx - 1, dy, dz) && inside.test(dx, dy + 1, dz)
                            && inside.test(dx, dy - 1, dz) && inside.test(dx, dy, dz + 1) && inside.test(dx, dy, dz - 1)) continue;
                    out.add(new BlockPos(cx + dx, cy + dy, cz + dz));
                }
        return out;
    }

    private static int radius(Args a, int i) {
        int rad = a.i(i);
        if (rad < 0) throw new PyError("ValueError", a.fn + "(): радиус не может быть отрицательным");
        if (rad > 200) throw new PyError("ValueError", a.fn + "(): радиус слишком большой (максимум 200)");
        return rad;
    }

    private void registerShapes() {
        def("Sphere", a -> {
            int rad = radius(a, 3);
            double r2 = (rad + 0.5) * (rad + 0.5);
            return (long) setAll(shape(a.i(0), a.i(1), a.i(2), rad, -rad, rad, rad, (x, y, z) -> x * x + y * y + z * z <= r2, false), block(a.s(4)));
        });
        def("HollowSphere", a -> {
            int rad = radius(a, 3);
            double r2 = (rad + 0.5) * (rad + 0.5);
            return (long) setAll(shape(a.i(0), a.i(1), a.i(2), rad, -rad, rad, rad, (x, y, z) -> x * x + y * y + z * z <= r2, true), block(a.s(4)));
        });
        def("Dome", a -> {
            int rad = radius(a, 3);
            double r2 = (rad + 0.5) * (rad + 0.5);
            Inside in = (x, y, z) -> y >= 0 && x * x + y * y + z * z <= r2;
            Inside shell = (x, y, z) -> in.test(x, y, z) || (y == -1 && x * x + z * z <= r2);
            List<BlockPos> list = new ArrayList<>();
            for (BlockPos p : shape(a.i(0), a.i(1), a.i(2), rad, 0, rad, rad, shell, true)) if (p.getY() >= a.i(1)) list.add(p);
            return (long) setAll(list, block(a.s(4)));
        });
        def("Cylinder", a -> {
            int rad = radius(a, 3), h = Math.max(1, a.i(4));
            double r2 = (rad + 0.5) * (rad + 0.5);
            return (long) setAll(shape(a.i(0), a.i(1), a.i(2), rad, 0, h - 1, rad, (x, y, z) -> x * x + z * z <= r2, false), block(a.s(5)));
        });
        def("HollowCylinder", a -> {
            int rad = radius(a, 3), h = Math.max(1, a.i(4));
            double r2 = (rad + 0.5) * (rad + 0.5);
            Inside in = (x, y, z) -> x * x + z * z <= r2;
            Inside ring = (x, y, z) -> in.test(x, 0, z) && !(in.test(x + 1, 0, z) && in.test(x - 1, 0, z) && in.test(x, 0, z + 1) && in.test(x, 0, z - 1));
            return (long) setAll(shape(a.i(0), a.i(1), a.i(2), rad, 0, h - 1, rad, ring, false), block(a.s(5)));
        });
        def("Circle", a -> {
            int rad = radius(a, 3);
            double r2 = (rad + 0.5) * (rad + 0.5);
            Inside in = (x, y, z) -> x * x + z * z <= r2;
            Inside ring = (x, y, z) -> in.test(x, 0, z) && !(in.test(x + 1, 0, z) && in.test(x - 1, 0, z) && in.test(x, 0, z + 1) && in.test(x, 0, z - 1));
            return (long) setAll(shape(a.i(0), a.i(1), a.i(2), rad, 0, 0, rad, ring, false), block(a.s(4)));
        });
        def("Disk", a -> {
            int rad = radius(a, 3);
            double r2 = (rad + 0.5) * (rad + 0.5);
            return (long) setAll(shape(a.i(0), a.i(1), a.i(2), rad, 0, 0, rad, (x, y, z) -> x * x + z * z <= r2, false), block(a.s(4)));
        });
        def("Pyramid", a -> {
            int x = a.i(0), y = a.i(1), z = a.i(2), size = radius(a, 3);
            BlockState st = block(a.s(4));
            boolean hollow = a.b(5);
            List<BlockPos> list = new ArrayList<>();
            for (int k = 0; k <= size; k++) {
                int s = size - k;
                for (int dx = -s; dx <= s; dx++)
                    for (int dz = -s; dz <= s; dz++) {
                        if (hollow && k < size && Math.abs(dx) != s && Math.abs(dz) != s) continue;
                        list.add(new BlockPos(x + dx, y + k, z + dz));
                    }
            }
            return (long) setAll(list, st);
        });
        def("House", a -> {
            int x = a.i(0), y = a.i(1), z = a.i(2);
            int w = Math.max(5, a.i(3)), d = Math.max(5, a.i(4)), h = Math.max(3, a.i(5));
            if (w > 64 || d > 64 || h > 32) throw new PyError("ValueError", "House(): слишком большой дом (максимум 64×64×32)");
            BlockState wall = block(a.s(6));
            BlockState roof = block(a.s(7));
            BlockState corner = wall.getBlock() == Blocks.OAK_PLANKS ? Blocks.OAK_LOG.defaultBlockState() : wall;
            int x2 = x + w - 1, z2 = z + d - 1;
            return r.sync(() -> {
                ServerLevel lv = level();
                BlockState air = Blocks.AIR.defaultBlockState();
                // clear the inside, floor
                for (int i = x; i <= x2; i++)
                    for (int k = z; k <= z2; k++) {
                        lv.setBlock(new BlockPos(i, y, k), wall, 2);
                        for (int j = 1; j <= h + (Math.min(w, d) + 1) / 2 + 1; j++) lv.setBlock(new BlockPos(i, y + j, k), air, 2);
                    }
                // walls
                for (int j = 1; j <= h; j++)
                    for (int i = x; i <= x2; i++)
                        for (int k = z; k <= z2; k++) {
                            boolean edgeX = i == x || i == x2, edgeZ = k == z || k == z2;
                            if (!edgeX && !edgeZ) continue;
                            lv.setBlock(new BlockPos(i, y + j, k), edgeX && edgeZ ? corner : wall, 2);
                        }
                // windows
                BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
                for (int i = x + 2; i <= x2 - 2; i += 3) {
                    lv.setBlock(new BlockPos(i, y + 2, z), pane, 3);
                    if (Math.abs(i - (x + w / 2)) > 1) lv.setBlock(new BlockPos(i, y + 2, z2), pane, 3);
                }
                for (int k = z + 2; k <= z2 - 2; k += 3) {
                    lv.setBlock(new BlockPos(x, y + 2, k), pane, 3);
                    lv.setBlock(new BlockPos(x2, y + 2, k), pane, 3);
                }
                // door in the middle of the south (z2) wall, facing the default camera
                int dx = x + w / 2;
                BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH)
                        .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT);
                lv.setBlock(new BlockPos(dx, y + 1, z2), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), 2);
                lv.setBlock(new BlockPos(dx, y + 2, z2), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);
                // stepped roof with an overhang
                int layer = 0;
                while (true) {
                    int ax = x - 1 + layer, bx = x2 + 1 - layer, az = z - 1 + layer, bz = z2 + 1 - layer;
                    if (ax > bx || az > bz) break;
                    boolean top = ax + 1 >= bx || az + 1 >= bz;
                    for (int i = ax; i <= bx; i++)
                        for (int k = az; k <= bz; k++) {
                            if (!top && i != ax && i != bx && k != az && k != bz) continue;
                            lv.setBlock(new BlockPos(i, y + h + 1 + layer, k), roof, 2);
                        }
                    if (top) break;
                    layer++;
                }
                // a ceiling and a light inside
                for (int i = x + 1; i < x2; i++) for (int k = z + 1; k < z2; k++) lv.setBlock(new BlockPos(i, y + h + 1, k), roof, 2);
                lv.setBlock(new BlockPos(x + w / 2, y + h, z + d / 2), Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true), 3);
                return null;
            });
        });
        def("Tower", a -> {
            int x = a.i(0), y = a.i(1), z = a.i(2), h = Math.max(2, a.i(3)), rad = Math.max(1, radius(a, 4));
            if (h > 256) throw new PyError("ValueError", "Tower(): слишком высокая башня");
            BlockState st = block(a.s(5));
            double r2 = (rad + 0.5) * (rad + 0.5);
            Inside in = (dx, dy, dz) -> dx * dx + dz * dz <= r2;
            Inside ring = (dx, dy, dz) -> in.test(dx, 0, dz) && !(in.test(dx + 1, 0, dz) && in.test(dx - 1, 0, dz) && in.test(dx, 0, dz + 1) && in.test(dx, 0, dz - 1));
            List<BlockPos> walls = shape(x, y, z, rad, 0, h - 1, rad, ring, false);
            List<BlockPos> battlements = new ArrayList<>();
            for (BlockPos p : shape(x, y + h, z, rad, 0, 0, rad, ring, false)) if (((p.getX() + p.getZ()) & 1) == 0) battlements.add(p);
            List<BlockPos> floor = shape(x, y + h - 1, z, rad, 0, 0, rad, in, false);
            setAll(walls, st);
            setAll(floor, st);
            setAll(battlements, st);
            setAll(List.of(new BlockPos(x, y, z - rad), new BlockPos(x, y + 1, z - rad)), Blocks.AIR.defaultBlockState());
            return null;
        });
        def("Tree", a -> {
            BlockPos p = pos(a, 0);
            String kind = a.s(3).toLowerCase(Locale.ROOT);
            String feature = switch (kind) {
                case "oak", "дуб" -> "oak";
                case "big_oak", "fancy_oak" -> "fancy_oak";
                case "birch", "берёза", "береза" -> "birch";
                case "spruce", "ель", "ёлка", "елка" -> "spruce";
                case "jungle" -> "jungle_tree";
                case "acacia" -> "acacia";
                case "dark_oak" -> "dark_oak";
                case "cherry", "сакура" -> "cherry";
                case "mangrove" -> "mangrove";
                case "azalea" -> "azalea_tree";
                case "mushroom", "red_mushroom" -> "huge_red_mushroom";
                case "brown_mushroom" -> "huge_brown_mushroom";
                default -> throw new PyError("ValueError", "Tree(): неизвестное дерево " + Py.repr(kind) + ". Есть: oak, big_oak, birch, spruce, jungle, acacia, dark_oak, cherry, mangrove, azalea, mushroom");
            };
            return r.sync(() -> {
                ServerLevel lv = level();
                Registry<ConfiguredFeature<?, ?>> reg = lv.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
                Holder<ConfiguredFeature<?, ?>> h = reg.getHolder(ResourceKey.create(Registries.CONFIGURED_FEATURE, new ResourceLocation(feature))).orElse(null);
                if (h == null) throw new PyError("WorldError", "дерево " + feature + " недоступно");
                BlockState below = lv.getBlockState(p.below());
                if (below.isAir()) lv.setBlock(p.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                boolean ok = h.value().place(lv, lv.getChunkSource().getGenerator(), lv.getRandom(), p);
                if (!ok) r.print("Tree(): дерево не поместилось в " + p.getX() + ", " + p.getY() + ", " + p.getZ(), true);
                return ok;
            });
        });
        def("Maze", a -> {
            int x = a.i(0), y = a.i(1), z = a.i(2), cw = a.i(3), cd = a.i(4), h = Math.max(1, a.i(5));
            if (cw < 2 || cd < 2 || cw > 100 || cd > 100) throw new PyError("ValueError", "Maze(): ширина и глубина от 2 до 100 клеток");
            BlockState st = block(a.s(6));
            int gw = cw * 2 + 1, gd = cd * 2 + 1;
            boolean[][] open = new boolean[gw][gd];
            java.util.Random rnd = new java.util.Random();
            java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
            open[1][1] = true;
            stack.push(new int[]{0, 0});
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!stack.isEmpty()) {
                int[] c = stack.peek();
                List<int[]> nb = new ArrayList<>();
                for (int[] dd : dirs) {
                    int nx = c[0] + dd[0], nz = c[1] + dd[1];
                    if (nx >= 0 && nz >= 0 && nx < cw && nz < cd && !open[nx * 2 + 1][nz * 2 + 1]) nb.add(new int[]{nx, nz, dd[0], dd[1]});
                }
                if (nb.isEmpty()) {
                    stack.pop();
                    continue;
                }
                int[] n = nb.get(rnd.nextInt(nb.size()));
                open[c[0] * 2 + 1 + n[2]][c[1] * 2 + 1 + n[3]] = true;
                open[n[0] * 2 + 1][n[1] * 2 + 1] = true;
                stack.push(new int[]{n[0], n[1]});
            }
            open[1][0] = true; // entrance
            open[gw - 2][gd - 1] = true; // exit
            List<BlockPos> walls = new ArrayList<>(), air = new ArrayList<>();
            for (int i = 0; i < gw; i++)
                for (int k = 0; k < gd; k++)
                    for (int j = 0; j < h; j++) (open[i][k] ? air : walls).add(new BlockPos(x + i, y + j, z + k));
            setAll(air, Blocks.AIR.defaultBlockState());
            setAll(walls, st);
            return null;
        });
        def("Stairs", a -> {
            int x = a.i(0), y = a.i(1), z = a.i(2), len = a.i(3);
            if (len < 1 || len > 300) throw new PyError("ValueError", "Stairs(): длина от 1 до 300");
            net.minecraft.core.Direction dir = net.minecraft.core.Direction.byName(a.s(4).toLowerCase(Locale.ROOT));
            if (dir == null || dir.getAxis().isVertical()) throw new PyError("ValueError", "Stairs(): направление north, south, east или west");
            BlockState st = block(a.s(5));
            if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, dir);
            List<BlockPos> list = new ArrayList<>();
            for (int i = 0; i < len; i++) list.add(new BlockPos(x + dir.getStepX() * i, y + i, z + dir.getStepZ() * i));
            return (long) setAll(list, st);
        });
    }

    // =====================================================================
    // mobs
    // =====================================================================
    private static final Set<String> FLAGS = Set.of("glowing", "noai", "baby", "silent", "invulnerable", "fire", "persistent", "nogravity", "named");

    private void applyOptions(Entity e, Object options) {
        if (options == null) return;
        Map<String, Object> opts = new HashMap<>();
        if (options instanceof String s) opts.put(s, true);
        else if (options instanceof PyDict d) for (Object k : d.keyList()) opts.put(Py.str(k).toLowerCase(Locale.ROOT), d.get(k));
        else for (Object o : Py.toList(options)) {
            if (o instanceof PyDict d) for (Object k : d.keyList()) opts.put(Py.str(k).toLowerCase(Locale.ROOT), d.get(k));
            else {
                String s = Py.str(o).toLowerCase(Locale.ROOT);
                int colon = s.indexOf(':');
                if (colon > 0 && !s.startsWith("minecraft:")) opts.put(s.substring(0, colon), Py.str(o).substring(colon + 1));
                else opts.put(s, true);
            }
        }
        for (var en : opts.entrySet()) {
            String k = en.getKey();
            Object v = en.getValue();
            boolean on = Py.truthy(v);
            switch (k) {
                case "glowing", "glow", "view", "visible" -> e.setGlowingTag(on);
                case "noai", "no_ai", "statue" -> { if (e instanceof Mob m) m.setNoAi(on); }
                case "ai" -> { if (e instanceof Mob m) m.setNoAi(!on); }
                case "baby", "small" -> { if (e instanceof Mob m) m.setBaby(on); }
                case "silent" -> e.setSilent(on);
                case "invulnerable", "god" -> e.setInvulnerable(on);
                case "fire", "burning" -> { if (on) e.setSecondsOnFire(Py.isNum(v) && !(v instanceof Boolean) ? (int) Py.toDouble(v) : 100000); }
                case "persistent" -> { if (e instanceof Mob m && on) m.setPersistenceRequired(); }
                case "nogravity", "no_gravity", "fly" -> e.setNoGravity(on);
                case "name" -> {
                    e.setCustomName(Component.literal(Py.str(v)));
                    e.setCustomNameVisible(true);
                }
                case "health", "hp" -> {
                    if (e instanceof LivingEntity le) {
                        float hp = (float) Py.toDouble(v);
                        AttributeInstance max = le.getAttribute(Attributes.MAX_HEALTH);
                        if (max != null && hp > le.getMaxHealth()) max.setBaseValue(hp);
                        le.setHealth(hp);
                    }
                }
                case "speed" -> {
                    if (e instanceof LivingEntity le && le.getAttribute(Attributes.MOVEMENT_SPEED) != null)
                        le.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(Py.toDouble(v));
                }
                case "effect", "effects" -> {
                    if (e instanceof LivingEntity le) {
                        List<Object> list = v instanceof String ? List.of(v) : Py.toList(v);
                        for (Object ef : list) le.addEffect(new MobEffectInstance(effect(Py.str(ef)), MobEffectInstance.INFINITE_DURATION, 0));
                    }
                }
                case "item", "hand", "weapon" -> { if (e instanceof LivingEntity le) le.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item(Py.str(v)))); }
                case "helmet", "head" -> { if (e instanceof LivingEntity le) le.setItemSlot(EquipmentSlot.HEAD, new ItemStack(item(Py.str(v)))); }
                case "armor" -> {
                    if (e instanceof LivingEntity le) {
                        String m = Py.str(v).toLowerCase(Locale.ROOT);
                        if (m.equals("gold")) m = "golden";
                        le.setItemSlot(EquipmentSlot.HEAD, new ItemStack(item(m + "_helmet")));
                        le.setItemSlot(EquipmentSlot.CHEST, new ItemStack(item(m + "_chestplate")));
                        le.setItemSlot(EquipmentSlot.LEGS, new ItemStack(item(m + "_leggings")));
                        le.setItemSlot(EquipmentSlot.FEET, new ItemStack(item(m + "_boots")));
                    }
                }
                default -> throw new PyError("ValueError", "SummonMob(): неизвестная опция " + Py.repr(k) +
                        ". Есть: glowing, noai, baby, silent, invulnerable, fire, nogravity, name, health, speed, effect, item, helmet, armor");
            }
        }
    }

    private boolean matches(Entity e, String filter) {
        if (e instanceof Player) return false;
        if (filter.equals("all")) return e instanceof LivingEntity;
        if (filter.equals("everything")) return true;
        return typeName(e).equals(filter);
    }

    private String mobFilter(Args a, int i) {
        String f = a.s(i).toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (f.equals("all") || f.equals("everything") || f.equals("все") || f.equals("*")) return f.equals("everything") ? f : "all";
        entityType(f);
        return f;
    }

    private List<Entity> mobsAround(String filter, double radius) {
        ServerPlayer p = r.player();
        List<Entity> out = new ArrayList<>();
        for (Entity e : p.serverLevel().getAllEntities()) {
            if (e == null || !e.isAlive() || !matches(e, filter)) continue;
            if (radius > 0 && e.distanceTo(p) > radius) continue;
            out.add(e);
        }
        return out;
    }

    private void registerMobs() {
        def("SummonMob", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2);
            EntityType<?> type = entityType(a.s(3));
            Object options = a.o(4);
            return r.sync(() -> {
                ServerLevel lv = level();
                Entity e = type.create(lv);
                if (e == null) throw new PyError("WorldError", "моба " + a.s(3) + " нельзя призвать");
                e.moveTo(x, y, z, lv.getRandom().nextFloat() * 360f, 0f);
                if (e instanceof Mob m) {
                    m.finalizeSpawn(lv, lv.getCurrentDifficultyAt(e.blockPosition()), MobSpawnType.COMMAND, null, null);
                    m.setPersistenceRequired();
                }
                applyOptions(e, options);
                if (!lv.tryAddFreshEntityWithPassengers(e)) throw new PyError("WorldError", "не получилось призвать моба");
                return (long) e.getId();
            });
        });
        def("Summon", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2);
            EntityType<?> type = entityType(a.s(3));
            String nbt = a.text(4);
            CompoundTag tag;
            try {
                tag = nbt.isBlank() ? new CompoundTag() : TagParser.parseTag(nbt);
            } catch (CommandSyntaxException e) {
                throw new PyError("ValueError", "Summon(): ошибка в NBT: " + e.getRawMessage().getString());
            }
            tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
            return r.sync(() -> {
                ServerLevel lv = level();
                Entity e = EntityType.loadEntityRecursive(tag, lv, en -> {
                    en.moveTo(x, y, z, en.getYRot(), en.getXRot());
                    return en;
                });
                if (e == null) throw new PyError("WorldError", "не получилось призвать " + a.s(3));
                if (e instanceof Mob m && nbt.isBlank())
                    m.finalizeSpawn(lv, lv.getCurrentDifficultyAt(e.blockPosition()), MobSpawnType.COMMAND, null, null);
                if (!lv.tryAddFreshEntityWithPassengers(e)) throw new PyError("WorldError", "не получилось призвать " + a.s(3));
                return (long) e.getId();
            });
        });
        def("SpawnItem", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2);
            Item it = item(a.s(3));
            int count = Mth.clamp(a.i(4), 1, 6400);
            return r.sync(() -> {
                ServerLevel lv = level();
                int left = count;
                long id = -1;
                while (left > 0) {
                    int n = Math.min(left, it.getMaxStackSize());
                    ItemEntity ie = new ItemEntity(lv, x, y, z, new ItemStack(it, n));
                    lv.addFreshEntity(ie);
                    id = ie.getId();
                    left -= n;
                }
                return id;
            });
        });
        def("KillMobs", a -> {
            String f = mobFilter(a, 0);
            double rad = a.d(1);
            return r.sync(() -> {
                long n = 0;
                for (Entity e : mobsAround(f, rad)) {
                    e.discard();
                    n++;
                }
                return n;
            });
        });
        def("CountMobs", a -> {
            String f = mobFilter(a, 0);
            double rad = a.d(1);
            return r.sync(() -> (long) mobsAround(f, rad).size());
        });
        def("FindMobs", a -> {
            String f = mobFilter(a, 0);
            double rad = a.d(1);
            return r.sync(() -> {
                PyList out = new PyList();
                for (Entity e : mobsAround(f, rad)) out.add((long) e.getId());
                return out;
            });
        });
        def("NearestMob", a -> {
            String f = mobFilter(a, 0);
            double rad = a.d(1);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                Entity best = null;
                double bd = Double.MAX_VALUE;
                for (Entity e : mobsAround(f, rad)) {
                    double d = e.distanceToSqr(p);
                    if (d < bd) {
                        bd = d;
                        best = e;
                    }
                }
                return best == null ? null : (Object) (long) best.getId();
            });
        });
        def("MobExists", a -> {
            int id = a.i(0);
            return r.sync(() -> {
                Entity e = level().getEntity(id);
                return e != null && e.isAlive() && !(e instanceof Player);
            });
        });
        def("MobType", a -> r.sync(() -> typeName(entity(a, 0))));
        def("MobPos", a -> r.sync(() -> {
            Entity e = entity(a, 0);
            BlockPos p = e.blockPosition();
            return new PyList(List.of((long) p.getX(), (long) p.getY(), (long) p.getZ()));
        }));
        def("MobX", a -> r.sync(() -> (long) entity(a, 0).blockPosition().getX()));
        def("MobY", a -> r.sync(() -> (long) entity(a, 0).blockPosition().getY()));
        def("MobZ", a -> r.sync(() -> (long) entity(a, 0).blockPosition().getZ()));
        def("MoveMob", a -> {
            double x = a.coord(1), y = a.d(2), z = a.coord(3);
            return r.sync(() -> {
                Entity e = entity(a, 0);
                e.teleportTo(x, y, z);
                return null;
            });
        });
        def("MobWalkTo", a -> {
            double x = a.coord(1), y = a.d(2), z = a.coord(3), speed = a.d(4);
            return r.sync(() -> mob(a, 0).getNavigation().moveTo(x, y, z, speed));
        });
        def("PushMob", a -> {
            double vx = a.d(1), vy = a.d(2), vz = a.d(3);
            return r.sync(() -> {
                Entity e = entity(a, 0);
                e.setDeltaMovement(vx, vy, vz);
                e.hurtMarked = true;
                return null;
            });
        });
        def("MobHealth", a -> r.sync(() -> (double) living(a, 0).getHealth()));
        def("SetMobHealth", a -> {
            float hp = (float) a.d(1);
            return r.sync(() -> {
                LivingEntity le = living(a, 0);
                AttributeInstance max = le.getAttribute(Attributes.MAX_HEALTH);
                if (max != null && hp > le.getMaxHealth()) max.setBaseValue(hp);
                le.setHealth(hp);
                return null;
            });
        });
        def("SetMobName", a -> {
            String name = a.text(1);
            return r.sync(() -> {
                Entity e = entity(a, 0);
                e.setCustomName(Component.literal(name));
                e.setCustomNameVisible(true);
                return null;
            });
        });
        def("SetMobGlow", a -> {
            boolean on = a.b(1);
            return r.sync(() -> {
                entity(a, 0).setGlowingTag(on);
                return null;
            });
        });
        def("SetMobAI", a -> {
            boolean on = a.b(1);
            return r.sync(() -> {
                mob(a, 0).setNoAi(!on);
                return null;
            });
        });
        def("SetBaby", a -> {
            boolean on = a.b(1);
            return r.sync(() -> {
                mob(a, 0).setBaby(on);
                return null;
            });
        });
        def("SetMobSpeed", a -> {
            double s = a.d(1);
            return r.sync(() -> {
                AttributeInstance at = living(a, 0).getAttribute(Attributes.MOVEMENT_SPEED);
                if (at == null) throw new PyError("WorldError", "у этого моба нет скорости");
                at.setBaseValue(s);
                return null;
            });
        });
        def("MobEffect", a -> {
            MobEffect ef = effect(a.s(1));
            int ticks = (int) Math.round(a.d(2) * 20), lvl = Mth.clamp(a.i(3), 1, 255);
            return r.sync(() -> living(a, 0).addEffect(new MobEffectInstance(ef, ticks, lvl - 1)));
        });
        def("MobRide", a -> r.sync(() -> entity(a, 0).startRiding(entity(a, 1), true)));
        def("KillMob", a -> r.sync(() -> {
            entity(a, 0).kill();
            return null;
        }));
    }

    // =====================================================================
    // player
    // =====================================================================
    private void registerPlayer() {
        def("Teleport", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                p.teleportTo(p.serverLevel(), x, y, z, p.getYRot(), p.getXRot());
                return null;
            });
        });
        def("Move", a -> {
            double dx = a.d(0), dy = a.d(1), dz = a.d(2);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                p.teleportTo(p.serverLevel(), p.getX() + dx, p.getY() + dy, p.getZ() + dz, p.getYRot(), p.getXRot());
                return null;
            });
        });
        def("Look", a -> {
            float yaw = (float) a.d(0), pitch = Mth.clamp((float) a.d(1), -90f, 90f);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), yaw, pitch);
                return null;
            });
        });
        def("LookAt", a -> {
            double x = a.coord(0), y = a.d(1) + (Py.isInt(a.o(1)) ? 0.5 : 0), z = a.coord(2);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                double dx = x - p.getX(), dy = y - p.getEyeY(), dz = z - p.getZ();
                float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
                float pitch = (float) (-(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG));
                p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), yaw, pitch);
                return null;
            });
        });
        def("PlayerPos", a -> r.sync(() -> {
            BlockPos b = r.player().blockPosition();
            return new PyList(List.of((long) b.getX(), (long) b.getY(), (long) b.getZ()));
        }));
        def("LookingAt", a -> r.sync(() -> {
            BlockPos b = lookPos();
            return b == null ? null : new PyList(List.of((long) b.getX(), (long) b.getY(), (long) b.getZ()));
        }));
        def("Give", a -> {
            Item it = item(a.s(0));
            int count = Mth.clamp(a.i(1), 1, 64 * 36);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                int left = count;
                while (left > 0) {
                    int n = Math.min(left, it.getMaxStackSize());
                    ItemStack st = new ItemStack(it, n);
                    if (!p.getInventory().add(st)) p.drop(st, false);
                    left -= n;
                }
                p.inventoryMenu.broadcastChanges();
                return null;
            });
        });
        def("ClearInventory", a -> r.sync(() -> {
            r.player().getInventory().clearContent();
            return null;
        }));
        def("HasItem", a -> {
            Item it = item(a.s(0));
            return r.sync(() -> (long) r.player().getInventory().countItem(it));
        });
        def("SetHealth", a -> {
            float hp = (float) a.d(0);
            return r.sync(() -> {
                r.player().setHealth(Mth.clamp(hp, 0, r.player().getMaxHealth()));
                return null;
            });
        });
        def("Heal", a -> r.sync(() -> {
            r.player().setHealth(r.player().getMaxHealth());
            return null;
        }));
        def("Feed", a -> r.sync(() -> {
            r.player().getFoodData().setFoodLevel(20);
            r.player().getFoodData().setSaturation(20f);
            return null;
        }));
        def("Effect", a -> {
            MobEffect ef = effect(a.s(0));
            int ticks = (int) Math.round(a.d(1) * 20), lvl = Mth.clamp(a.i(2), 1, 255);
            return r.sync(() -> r.player().addEffect(new MobEffectInstance(ef, ticks, lvl - 1)));
        });
        def("ClearEffects", a -> r.sync(() -> r.player().removeAllEffects()));
        def("Gamemode", a -> {
            String m = a.s(0).toLowerCase(Locale.ROOT);
            GameType gt = switch (m) {
                case "наблюдатель", "spectator", "sp" -> GameType.SPECTATOR;
                case "творческий", "креатив", "creative", "c" -> GameType.CREATIVE;
                case "выживание", "survival", "s" -> GameType.SURVIVAL;
                case "приключение", "adventure", "a" -> GameType.ADVENTURE;
                default -> throw new PyError("ValueError", "Gamemode(): режим spectator, creative, survival или adventure");
            };
            return r.sync(() -> r.player().setGameMode(gt));
        });
        def("SetXP", a -> {
            int lv = Math.max(0, a.i(0));
            return r.sync(() -> {
                r.player().setExperienceLevels(lv);
                return null;
            });
        });
        def("AddXP", a -> {
            int lv = a.i(0);
            return r.sync(() -> {
                r.player().giveExperienceLevels(lv);
                return null;
            });
        });
        def("SetSpawn", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> {
                ServerPlayer pl = r.player();
                pl.setRespawnPosition(pl.serverLevel().dimension(), p, 0, true, false);
                return null;
            });
        });
        def("SetFlySpeed", a -> {
            float s = Mth.clamp((float) a.d(0), 0f, 1f);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                p.getAbilities().setFlyingSpeed(s);
                p.onUpdateAbilities();
                return null;
            });
        });
        def("Launch", a -> {
            double vx = a.d(0), vy = a.d(1), vz = a.d(2);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                p.setDeltaMovement(vx, vy, vz);
                p.connection.send(new ClientboundSetEntityMotionPacket(p));
                return null;
            });
        });
        def("PlayerInArea", a -> {
            int x1 = Math.min(a.i(0), a.i(3)), x2 = Math.max(a.i(0), a.i(3));
            int y1 = Math.min(a.i(1), a.i(4)), y2 = Math.max(a.i(1), a.i(4));
            int z1 = Math.min(a.i(2), a.i(5)), z2 = Math.max(a.i(2), a.i(5));
            return r.sync(() -> {
                BlockPos b = r.player().blockPosition();
                return b.getX() >= x1 && b.getX() <= x2 && b.getY() >= y1 && b.getY() <= y2 && b.getZ() >= z1 && b.getZ() <= z2;
            });
        });
        def("PlayerNear", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2), rad = a.d(3);
            return r.sync(() -> r.player().position().distanceTo(new Vec3(x, y, z)) <= rad);
        });
        def("Players", a -> r.sync(() -> {
            PyList out = new PyList();
            for (ServerPlayer p : r.server.getPlayerList().getPlayers()) out.add(p.getGameProfile().getName());
            return out;
        }));
    }

    private BlockPos lookPos() {
        ServerPlayer p = r.player();
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(100));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    // =====================================================================
    // world
    // =====================================================================
    private void registerWorld() {
        def("SetTime", a -> {
            Object v = a.o(0);
            long t;
            if (v instanceof String s) {
                t = switch (s.toLowerCase(Locale.ROOT)) {
                    case "day", "день" -> 1000;
                    case "morning", "утро", "sunrise" -> 0;
                    case "noon", "полдень" -> 6000;
                    case "evening", "вечер", "sunset" -> 12000;
                    case "night", "ночь" -> 13000;
                    case "midnight", "полночь" -> 18000;
                    default -> throw new PyError("ValueError", "SetTime(): day, noon, evening, night, midnight, morning или число");
                };
            } else {
                t = (long) a.d(0);
            }
            return r.sync(() -> {
                for (ServerLevel lv : r.server.getAllLevels()) {
                    long day = lv.getDayTime() / 24000L;
                    lv.setDayTime(day * 24000L + Math.floorMod(t, 24000L));
                }
                return null;
            });
        });
        def("GetTime", a -> r.sync(() -> level().getDayTime() % 24000L));
        def("SetWeather", a -> {
            String k = a.s(0).toLowerCase(Locale.ROOT);
            int ticks = (int) Math.max(20, a.d(1) * 20);
            return r.sync(() -> {
                ServerLevel lv = r.server.overworld();
                switch (k) {
                    case "clear", "sun", "ясно", "солнце" -> lv.setWeatherParameters(ticks, 0, false, false);
                    case "rain", "дождь", "snow", "снег" -> lv.setWeatherParameters(0, ticks, true, false);
                    case "thunder", "storm", "гроза" -> lv.setWeatherParameters(0, ticks, true, true);
                    default -> throw new PyError("ValueError", "SetWeather(): \"clear\", \"rain\" или \"thunder\"");
                }
                return null;
            });
        });
        def("GetWeather", a -> r.sync(this::weather));
        def("DayCycle", a -> {
            boolean on = a.b(0);
            return r.sync(() -> {
                r.server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(on, r.server);
                return null;
            });
        });
        def("Gamerule", a -> {
            String name = a.s(0);
            Object v = a.o(1);
            String value = v instanceof Boolean b ? (b ? "true" : "false") : Py.str(v);
            if (!name.matches("[A-Za-z]+")) throw new PyError("ValueError", "Gamerule(): неправильное имя правила");
            return r.sync(() -> runCommand("gamerule " + name + " " + value));
        });
        def("Difficulty", a -> {
            String s = a.s(0).toLowerCase(Locale.ROOT);
            Difficulty d = switch (s) {
                case "мирный", "peaceful" -> Difficulty.PEACEFUL;
                case "лёгкий", "легкий", "easy" -> Difficulty.EASY;
                case "нормальный", "normal" -> Difficulty.NORMAL;
                case "сложный", "hard" -> Difficulty.HARD;
                default -> throw new PyError("ValueError", "Difficulty(): peaceful, easy, normal или hard");
            };
            return r.sync(() -> {
                r.server.setDifficulty(d, true);
                return null;
            });
        });
        def("Command", a -> {
            String cmd = a.s(0).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            String c = cmd;
            return r.sync(() -> runCommand(c));
        });
        def("SetWorldSpawn", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> {
                r.server.overworld().setDefaultSpawnPos(p, 0);
                return null;
            });
        });
        def("Biome", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> level().getBiome(p).unwrapKey().map(k -> k.location().getPath()).orElse("unknown"));
        });
        def("LightLevel", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> (long) level().getMaxLocalRawBrightness(p));
        });
    }

    private String weather() {
        ServerLevel lv = r.server.overworld();
        return lv.isThundering() ? "thunder" : lv.isRaining() ? "rain" : "clear";
    }

    private long runCommand(String cmd) {
        ServerPlayer p = r.player();
        CommandSource out = new CommandSource() {
            @Override
            public void sendSystemMessage(Component c) {
                r.print("[команда] " + c.getString(), false);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack src = new CommandSourceStack(out, p.position(), new Vec2(p.getXRot(), p.getYRot()), p.serverLevel(), 4,
                p.getName().getString(), p.getDisplayName(), r.server, p);
        return r.server.getCommands().performPrefixedCommand(src, cmd);
    }

    // =====================================================================
    // effects
    // =====================================================================
    private void registerEffects() {
        def("Explosion", a -> {
            double x = a.coord(0), y = a.d(1) + (Py.isInt(a.o(1)) ? 0.5 : 0), z = a.coord(2);
            float power = (float) Mth.clamp(a.d(3), 0, 20);
            boolean fire = a.b(4), breaks = a.b(5);
            return r.sync(() -> {
                level().explode(null, x, y, z, power, fire, breaks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
                return null;
            });
        });
        def("Lightning", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2);
            return r.sync(() -> {
                ServerLevel lv = level();
                Entity bolt = EntityType.LIGHTNING_BOLT.create(lv);
                if (bolt == null) return null;
                bolt.moveTo(x, y, z);
                lv.addFreshEntity(bolt);
                return null;
            });
        });
        def("Particle", a -> {
            double x = a.coord(0), y = a.d(1) + (Py.isInt(a.o(1)) ? 0.5 : 0), z = a.coord(2);
            ParticleOptions pt = particle(a.s(3));
            int count = Mth.clamp(a.i(4), 1, 5000);
            double spread = a.d(5);
            return r.sync(() -> {
                level().sendParticles(pt, x, y, z, count, spread, spread, spread, 0.02);
                return null;
            });
        });
        def("Sound", a -> {
            SoundEvent snd = sound(a.s(0));
            boolean here = a.isNone(1) || a.isNone(2) || a.isNone(3);
            double x = here ? 0 : a.coord(1), y = here ? 0 : a.d(2), z = here ? 0 : a.coord(3);
            float vol = (float) a.d(4), pitch = (float) Mth.clamp(a.d(5), 0.1, 4);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                if (here) p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), snd, SoundSource.MASTER, vol, pitch);
                else p.serverLevel().playSound(null, x, y, z, snd, SoundSource.MASTER, vol, pitch);
                return null;
            });
        });
        def("Firework", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2);
            DyeColor color = DyeColor.byName(a.s(3).toLowerCase(Locale.ROOT), null);
            if (color == null) throw new PyError("ValueError", "Firework(): неизвестный цвет " + Py.repr(a.s(3)));
            int power = Mth.clamp(a.i(4), 0, 3);
            return r.sync(() -> {
                ServerLevel lv = level();
                ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
                CompoundTag fw = new CompoundTag();
                fw.putByte("Flight", (byte) power);
                ListTag explosions = new ListTag();
                CompoundTag ex = new CompoundTag();
                ex.putByte("Type", (byte) lv.getRandom().nextInt(5));
                ex.put("Colors", new IntArrayTag(new int[]{color.getFireworkColor()}));
                ex.putBoolean("Flicker", true);
                ex.putBoolean("Trail", true);
                explosions.add(ex);
                fw.put("Explosions", explosions);
                rocket.getOrCreateTag().put("Fireworks", fw);
                lv.addFreshEntity(new FireworkRocketEntity(lv, x, y, z, rocket));
                return null;
            });
        });
    }

    // =====================================================================
    // messages, time, utilities
    // =====================================================================
    private void registerMessages(Interpreter in) {
        def("Say", a -> {
            String t = a.text(0);
            return r.sync(() -> {
                r.server.getPlayerList().broadcastSystemMessage(Component.literal("§b[Код]§r " + t), false);
                return null;
            });
        });
        def("Title", a -> {
            String t = a.text(0), sub = a.text(1);
            int ticks = (int) Math.max(10, a.d(2) * 20);
            return r.sync(() -> {
                ServerPlayer p = r.player();
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, ticks, 10));
                p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(sub)));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(t)));
                return null;
            });
        });
        def("ActionBar", a -> {
            String t = a.text(0);
            return r.sync(() -> {
                r.player().connection.send(new ClientboundSetActionBarTextPacket(Component.literal(t)));
                return null;
            });
        });
        def("ClearConsole", a -> {
            r.clearConsole();
            return null;
        });
        def("Help", a -> {
            if (a.isNone(0)) {
                String cat = "";
                StringBuilder line = new StringBuilder();
                for (ApiDocs.Fn f : ApiDocs.FUNCTIONS) {
                    if (!f.category().equals(cat)) {
                        if (line.length() > 0) r.print(line.toString(), false);
                        line.setLength(0);
                        cat = f.category();
                        r.print("— " + cat + " —", false);
                    }
                    if (line.length() > 0) line.append(", ");
                    line.append(f.name());
                    if (line.length() > 60) {
                        r.print(line.toString(), false);
                        line.setLength(0);
                    }
                }
                if (line.length() > 0) r.print(line.toString(), false);
                StringBuilder vars = new StringBuilder("— Переменные — ");
                for (ApiDocs.Var v : ApiDocs.VARIABLES) vars.append(v.name()).append(' ');
                r.print(vars.toString(), false);
                r.print("Подробнее: Help(\"SummonMob\") или кнопка «Справка»", false);
                return null;
            }
            String name = a.text(0);
            ApiDocs.Fn f = ApiDocs.BY_NAME.get(name);
            if (f == null) {
                for (ApiDocs.Var v : ApiDocs.VARIABLES)
                    if (v.name().equals(name)) {
                        r.print(v.name() + " — " + v.desc(), false);
                        return null;
                    }
                throw new PyError("NameError", "нет функции " + Py.repr(name));
            }
            r.print(f.signature(), false);
            r.print("  " + f.desc(), false);
            r.print("  Пример:", false);
            for (String l : f.example().split("\n")) r.print("    " + l, false);
            return null;
        });
    }

    private void registerTime(Interpreter in) {
        def("wait", a -> {
            r.sleepTicks(Math.max(0, (long) Math.round(a.d(0))));
            return null;
        });
        def("Wait", impls.get("wait"));
        def("WaitSeconds", a -> {
            r.sleepTicks(Math.max(0, Math.round(a.d(0) * 20)));
            return null;
        });
        def("sleep", impls.get("WaitSeconds"));
        def("WaitUntil", a -> {
            Object cond = a.o(0);
            long timeout = (long) a.d(1);
            long start = r.tick;
            while (!Py.truthy(in.callValue(cond, List.of()))) {
                if (timeout > 0 && r.tick - start >= timeout) return false;
                r.sleepTicks(1);
            }
            return true;
        });
        def("Ticks", a -> r.tick);
        def("Stop", a -> {
            throw new Interpreter.ScriptStopped(true);
        });
        def("OnEvent", a -> {
            String ev = a.s(0).toLowerCase(Locale.ROOT);
            if (ev.startsWith("on_")) ev = ev.substring(3);
            boolean known = false;
            for (String n : ScriptRunner.EVENT_NAMES) known |= n.equals(ev);
            if (!known) throw new PyError("ValueError", "OnEvent(): неизвестное событие " + Py.repr(ev) + ". Есть: " + String.join(", ", ScriptRunner.EVENT_NAMES));
            String event = ev;
            if (a.isNone(1)) {
                // decorator form: @OnEvent("chat")
                return new com.miramwh.codingworld.py.Values.Builtin("OnEvent." + event, (it, args, kw) -> {
                    r.addHandler(event, args.get(0));
                    return args.get(0);
                });
            }
            if (!(a.o(1) instanceof com.miramwh.codingworld.py.Values.PyCallable))
                throw new PyError("TypeError", "OnEvent(): второй аргумент — функция (без скобок!)");
            r.addHandler(event, a.o(1));
            return null;
        });
        def("Every", a -> {
            long t = Math.max(1, (long) a.d(0));
            if (!(a.o(1) instanceof com.miramwh.codingworld.py.Values.PyCallable)) throw new PyError("TypeError", "Every(): второй аргумент — функция (без скобок!)");
            r.timers.add(new ScriptRunner.Timer(t, r.tick + t, a.o(1), true));
            CodingServer.sendStatus(r, true);
            return null;
        });
        def("After", a -> {
            long t = Math.max(1, (long) a.d(0));
            if (!(a.o(1) instanceof com.miramwh.codingworld.py.Values.PyCallable)) throw new PyError("TypeError", "After(): второй аргумент — функция (без скобок!)");
            r.timers.add(new ScriptRunner.Timer(t, r.tick + t, a.o(1), false));
            return null;
        });
    }

    private void registerUtil(Interpreter in) {
        def("Random", a -> {
            long lo = (long) a.d(0), hi = (long) a.d(1);
            if (hi < lo) {
                long t = lo;
                lo = hi;
                hi = t;
            }
            return lo + (long) Math.floor(in.random.nextDouble() * (hi - lo + 1));
        });
        def("randint", impls.get("Random"));
        def("RandomFloat", a -> in.random.nextDouble());
        def("Choice", a -> {
            List<Object> l = Py.toList(a.o(0));
            if (l.isEmpty()) throw new PyError("IndexError", "Choice() из пустого списка");
            return l.get(in.random.nextInt(l.size()));
        });
        def("Distance", a -> {
            double dx = a.d(0) - a.d(3), dy = a.d(1) - a.d(4), dz = a.d(2) - a.d(5);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        });
        def("DistanceToPlayer", a -> {
            double x = a.coord(0), y = a.d(1), z = a.coord(2);
            return r.sync(() -> r.player().position().distanceTo(new Vec3(x, y, z)));
        });
        def("Blocks", a -> {
            String f = a.text(0).toLowerCase(Locale.ROOT);
            PyList out = new PyList();
            List<String> names = new ArrayList<>();
            for (ResourceLocation k : BuiltInRegistries.BLOCK.keySet()) if (k.getPath().contains(f)) names.add(k.getNamespace().equals("minecraft") ? k.getPath() : k.toString());
            Collections.sort(names);
            out.addAll(names);
            return out;
        });
        def("Mobs", a -> {
            String f = a.text(0).toLowerCase(Locale.ROOT);
            PyList out = new PyList();
            List<String> names = new ArrayList<>();
            for (ResourceLocation k : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(k);
                if (k.getPath().contains(f) && t.canSummon() && t != EntityType.PLAYER) names.add(k.getNamespace().equals("minecraft") ? k.getPath() : k.toString());
            }
            Collections.sort(names);
            out.addAll(names);
            return out;
        });
    }

    // =====================================================================
    // live variables
    // =====================================================================
    private static final Set<String> VAR_NAMES = new java.util.HashSet<>();

    static {
        for (ApiDocs.Var v : ApiDocs.VARIABLES) VAR_NAMES.add(v.name());
    }

    Object variable(String name) {
        if (!VAR_NAMES.contains(name)) return Interpreter.NO_VALUE;
        if (name.equals("Tick")) return r.tick;
        return r.sync(() -> {
            ServerPlayer p = r.player();
            ServerLevel lv = p.serverLevel();
            BlockPos b = p.blockPosition();
            return switch (name) {
                case "PlayerX", "Xplayer" -> (Object) (long) b.getX();
                case "PlayerY", "Yplayer" -> (long) b.getY();
                case "PlayerZ", "Zplayer" -> (long) b.getZ();
                case "PlayerExactX" -> p.getX();
                case "PlayerExactY" -> p.getY();
                case "PlayerExactZ" -> p.getZ();
                case "PlayerYaw" -> (double) Mth.wrapDegrees(p.getYRot());
                case "PlayerPitch" -> (double) p.getXRot();
                case "PlayerHealth" -> (double) p.getHealth();
                case "PlayerFood" -> (long) p.getFoodData().getFoodLevel();
                case "PlayerXP" -> (long) p.experienceLevel;
                case "PlayerName" -> p.getGameProfile().getName();
                case "PlayerMode" -> p.gameMode.getGameModeForPlayer().getName();
                case "PlayerOnGround" -> p.onGround();
                case "PlayerInWater" -> p.isInWater();
                case "PlayerSneaking" -> p.isShiftKeyDown();
                case "PlayerBlock" -> blockName(lv.getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ())));
                case "PlayerDimension" -> lv.dimension().location().getPath();
                case "LookX", "LookY", "LookZ", "LookBlock" -> {
                    BlockPos lp = lookPos();
                    if (lp == null) yield null;
                    yield switch (name) {
                        case "LookX" -> (Object) (long) lp.getX();
                        case "LookY" -> (long) lp.getY();
                        case "LookZ" -> (long) lp.getZ();
                        default -> blockName(lv.getBlockState(lp));
                    };
                }
                case "Time" -> lv.getDayTime() % 24000L;
                case "Day" -> lv.getDayTime() / 24000L;
                case "IsDay" -> { long t = lv.getDayTime() % 24000L; yield t < 12300 || t > 23850; }
                case "IsNight" -> { long t = lv.getDayTime() % 24000L; yield t >= 12300 && t <= 23850; }
                case "Weather" -> weather();
                case "IsRaining" -> r.server.overworld().isRaining();
                case "IsThundering" -> r.server.overworld().isThundering();
                case "MobCount" -> {
                    long n = 0;
                    for (Entity e : lv.getAllEntities()) if (e instanceof Mob && e.isAlive()) n++;
                    yield n;
                }
                case "PlayerCount" -> (long) r.server.getPlayerCount();
                default -> null;
            };
        });
    }

    // =====================================================================
    // redstone
    // =====================================================================
    static int redstonePower(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        int power = level.getBestNeighborSignal(p);
        if (st.hasProperty(BlockStateProperties.POWER)) power = Math.max(power, st.getValue(BlockStateProperties.POWER));
        if (st.hasProperty(BlockStateProperties.POWERED) && st.getValue(BlockStateProperties.POWERED) && st.isSignalSource()) power = Math.max(power, 15);
        return power;
    }

    private static int power(Args a, int i) {
        int v = a.i(i);
        if (v < 0 || v > 15) throw new PyError("ValueError", a.fn + "(): сила сигнала должна быть от 0 до 15, а получено " + v);
        return v;
    }

    /** Switches a lever, button, door, trapdoor, gate or lamp. on == null toggles. Returns the new state. */
    private static boolean setPowered(ServerLevel level, BlockPos p, Boolean on) {
        BlockState st = level.getBlockState(p);
        Block b = st.getBlock();
        if (b instanceof net.minecraft.world.level.block.LeverBlock lever) {
            boolean now = st.getValue(BlockStateProperties.POWERED);
            if (on == null || on != now) st = lever.pull(st, level, p);
            return st.getValue(BlockStateProperties.POWERED);
        }
        if (b instanceof net.minecraft.world.level.block.ButtonBlock button) {
            if ((on == null || on) && !st.getValue(BlockStateProperties.POWERED)) button.press(st, level, p);
            return true;
        }
        if (b instanceof net.minecraft.world.level.block.DoorBlock door) {
            boolean open = on == null ? !st.getValue(BlockStateProperties.OPEN) : on;
            door.setOpen(null, level, st, p, open);
            return open;
        }
        for (var prop : List.of(BlockStateProperties.OPEN, BlockStateProperties.LIT, BlockStateProperties.POWERED)) {
            if (st.hasProperty(prop)) {
                boolean v = on == null ? !st.getValue(prop) : on;
                level.setBlock(p, st.setValue(prop, v), Block.UPDATE_ALL);
                return v;
            }
        }
        throw new PyError("WorldError", "блок " + blockName(st) + " нельзя включить или выключить. Подходят рычаг, кнопка, дверь, люк, калитка, лампа");
    }

    private void registerRedstone() {
        def("RedstonePower", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> (long) redstonePower(level(), p));
        });
        def("IsPowered", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> level().hasNeighborSignal(p) || redstonePower(level(), p) > 0);
        });
        def("HasRedstone", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> {
                BlockState st = level().getBlockState(p);
                return st.is(Blocks.REDSTONE_WIRE) || st.isSignalSource() || st.hasProperty(BlockStateProperties.POWERED)
                        || st.hasProperty(BlockStateProperties.POWER) || st.is(Blocks.REDSTONE_LAMP) || st.is(Blocks.PISTON) || st.is(Blocks.STICKY_PISTON);
            });
        });
        def("SetSignal", a -> {
            BlockPos p = pos(a, 0);
            int pw = power(a, 3);
            r.async(() -> level().setBlock(p, com.miramwh.codingworld.content.ModContent.SIGNAL.get().defaultBlockState()
                    .setValue(com.miramwh.codingworld.content.SignalBlock.POWER, pw), Block.UPDATE_ALL));
            return null;
        });
        def("Pulse", a -> {
            BlockPos p = pos(a, 0);
            int pw = power(a, 3);
            int ticks = Math.max(1, Math.min(72000, a.i(4)));
            r.async(() -> {
                Block sig = com.miramwh.codingworld.content.ModContent.SIGNAL.get();
                level().setBlock(p, sig.defaultBlockState().setValue(com.miramwh.codingworld.content.SignalBlock.POWER, pw), Block.UPDATE_ALL);
                level().scheduleTick(p, sig, ticks);
            });
            return null;
        });
        def("ClearSignal", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> level().getBlockState(p).getBlock() instanceof com.miramwh.codingworld.content.SignalBlock && level().removeBlock(p, false));
        });
        def("Toggle", a -> {
            BlockPos p = pos(a, 0);
            return r.sync(() -> setPowered(level(), p, null));
        });
        def("SetPowered", a -> {
            BlockPos p = pos(a, 0);
            boolean on = a.b(3);
            return r.sync(() -> setPowered(level(), p, on));
        });
        def("WatchRedstone", a -> {
            BlockPos p = pos(a, 0);
            Object fn = a.o(3);
            if (fn == null) {
                String where = "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
                fn = new com.miramwh.codingworld.py.Values.Builtin("WatchRedstone.print", (it, args, kw) -> {
                    r.print("Редстоун " + where + ": сила " + Py.str(args.get(0)), false);
                    return null;
                });
            } else if (!(fn instanceof com.miramwh.codingworld.py.Values.PyCallable)) {
                throw new PyError("TypeError", "WatchRedstone(): четвёртый аргумент — имя функции без скобок, например WatchRedstone(0, 0, 0, changed)");
            }
            int now = r.sync(() -> redstonePower(level(), p));
            r.watches.add(new Object[]{p, fn, now});
            CodingServer.sendStatus(r, true);
            return null;
        });
        def("AllClear", a -> {
            r.clearAll();
            return null;
        });
        def("GiveBook", a -> {
            r.sync(() -> r.player().getInventory().add(new ItemStack(com.miramwh.codingworld.content.ModContent.CODE_TEACH.get())));
            return null;
        });
    }

    // =====================================================================
    // crafting recipes made by scripts
    // =====================================================================
    private static final Set<ResourceLocation> SCRIPT_RECIPES = ConcurrentHashMap.newKeySet();
    /** Built-in recipes hidden because a script recipe uses the same pattern; ClearCrafts brings them back. */
    private static final List<net.minecraft.world.item.crafting.Recipe<?>> HIDDEN_RECIPES = new ArrayList<>();
    private boolean warnedSpectator = false;

    /**
     * Adds a script recipe. Any other crafting recipe with the same pattern (for example the vanilla torch recipe)
     * is hidden first, otherwise the game could keep using it and the script recipe would never show up.
     */
    private void addScriptRecipe(net.minecraft.world.item.crafting.Recipe<?> recipe, List<ItemStack> layout) {
        r.sync(() -> {
            net.minecraft.core.NonNullList<ItemStack> items = net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < layout.size() && i < 9; i++) items.set(i, layout.get(i));
            var grid = new net.minecraft.world.inventory.TransientCraftingContainer(null, 3, 3, items);
            ServerLevel level = level();
            updateRecipes(all -> {
                all.removeIf(x -> {
                    if (x.getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING) return false;
                    boolean same;
                    try {
                        same = ((net.minecraft.world.item.crafting.CraftingRecipe) x).matches(grid, level);
                    } catch (RuntimeException e) {
                        same = false;
                    }
                    if (!same) return false;
                    if (!SCRIPT_RECIPES.remove(x.getId())) HIDDEN_RECIPES.add(x);
                    return true;
                });
                all.add(recipe);
            });
            SCRIPT_RECIPES.add(recipe.getId());
            ServerPlayer p = r.player();
            if (p.isSpectator() && !warnedSpectator) {
                warnedSpectator = true;
                r.print("Подсказка: в режиме наблюдателя крафтить нельзя. Включи выживание: Command(\"gamemode survival\")", false);
            }
            return null;
        });
    }

    private static ItemStack sample(net.minecraft.world.item.crafting.Ingredient ing) {
        ItemStack[] st = ing.getItems();
        return ing.isEmpty() || st.length == 0 ? ItemStack.EMPTY : st[0].copyWithCount(1);
    }
    private static int recipeCounter = 0;

    private static net.minecraft.world.item.crafting.Ingredient ingredient(Object v) {
        if (v == null) return net.minecraft.world.item.crafting.Ingredient.EMPTY;
        String s = Py.str(v).trim();
        if (s.isEmpty() || s.equals("air") || s.equals("minecraft:air")) return net.minecraft.world.item.crafting.Ingredient.EMPTY;
        return net.minecraft.world.item.crafting.Ingredient.of(item(s));
    }

    private ItemStack result(Args a, int itemIdx, int countIdx) {
        Item it = item(a.s(itemIdx));
        if (it == Items.AIR) throw new PyError("ValueError", a.fn + "(): результат крафта не может быть воздухом");
        int count = Math.max(1, Math.min(64, a.i(countIdx)));
        return new ItemStack(it, count);
    }

    /** Replaces the server's recipes and tells every client. Must run on the server thread. */
    private void updateRecipes(java.util.function.Consumer<List<net.minecraft.world.item.crafting.Recipe<?>>> change) {
        var rm = r.server.getRecipeManager();
        List<net.minecraft.world.item.crafting.Recipe<?>> all = new ArrayList<>(rm.getRecipes());
        change.accept(all);
        rm.replaceRecipes(all);
        r.server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(rm.getRecipes()));
    }

    private void registerCrafts() {
        def("Craft", a -> {
            net.minecraft.world.item.crafting.Ingredient[] cells = new net.minecraft.world.item.crafting.Ingredient[9];
            for (int i = 0; i < 9; i++) cells[i] = ingredient(a.o(i));
            ItemStack out = result(a, 9, 10);
            int minX = 3, minY = 3, maxX = -1, maxY = -1;
            for (int i = 0; i < 9; i++) {
                if (cells[i].isEmpty()) continue;
                minX = Math.min(minX, i % 3);
                maxX = Math.max(maxX, i % 3);
                minY = Math.min(minY, i / 3);
                maxY = Math.max(maxY, i / 3);
            }
            if (maxX < 0) throw new PyError("ValueError", "Craft(): все клетки пустые — положите хотя бы один предмет");
            int w = maxX - minX + 1, h = maxY - minY + 1;
            net.minecraft.core.NonNullList<net.minecraft.world.item.crafting.Ingredient> grid =
                    net.minecraft.core.NonNullList.withSize(w * h, net.minecraft.world.item.crafting.Ingredient.EMPTY);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) grid.set(y * w + x, cells[(minY + y) * 3 + minX + x]);
            ResourceLocation id = new ResourceLocation("codingworld", "script_" + (++recipeCounter));
            var recipe = new net.minecraft.world.item.crafting.ShapedRecipe(id, "", net.minecraft.world.item.crafting.CraftingBookCategory.MISC, w, h, grid, out);
            List<ItemStack> layout = new ArrayList<>();
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) layout.add(x < w && y < h ? sample(grid.get(y * w + x)) : ItemStack.EMPTY);
            addScriptRecipe(recipe, layout);
            return id.getPath();
        });
        def("CraftShapeless", a -> {
            if (!(a.o(0) instanceof PyList list) || list.isEmpty() || list.size() > 9)
                throw new PyError("TypeError", "CraftShapeless(): первый аргумент — список от 1 до 9 предметов, например [\"sand\", \"gunpowder\"]");
            net.minecraft.core.NonNullList<net.minecraft.world.item.crafting.Ingredient> ings = net.minecraft.core.NonNullList.create();
            for (Object o : list) {
                var ing = ingredient(o);
                if (!ing.isEmpty()) ings.add(ing);
            }
            if (ings.isEmpty()) throw new PyError("ValueError", "CraftShapeless(): список пустой");
            ItemStack out = result(a, 1, 2);
            ResourceLocation id = new ResourceLocation("codingworld", "script_" + (++recipeCounter));
            var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe(id, "", net.minecraft.world.item.crafting.CraftingBookCategory.MISC, out, ings);
            List<ItemStack> layout = new ArrayList<>();
            for (var ing : ings) layout.add(sample(ing));
            addScriptRecipe(recipe, layout);
            return id.getPath();
        });
        def("ClearCrafts", a -> {
            return r.sync(() -> {
                int n = SCRIPT_RECIPES.size();
                updateRecipes(all -> {
                    all.removeIf(x -> SCRIPT_RECIPES.contains(x.getId()));
                    all.addAll(HIDDEN_RECIPES);
                });
                SCRIPT_RECIPES.clear();
                HIDDEN_RECIPES.clear();
                return (long) n;
            });
        });
    }
}
