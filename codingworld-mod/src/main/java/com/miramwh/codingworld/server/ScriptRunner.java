package com.miramwh.codingworld.server;

import com.miramwh.codingworld.CodingWorldMod;
import com.miramwh.codingworld.api.ApiDocs;
import com.miramwh.codingworld.net.ConsolePacket;
import com.miramwh.codingworld.net.Network;
import com.miramwh.codingworld.py.Interpreter;
import com.miramwh.codingworld.py.Py;
import com.miramwh.codingworld.py.PyError;
import com.miramwh.codingworld.py.Values;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Runs one player's script on its own thread; world access is marshalled to the server thread. */
public final class ScriptRunner implements Interpreter.Host {
    public static final String[] EVENT_NAMES = {"tick", "chat", "block_break", "block_place", "mob_death", "player_move", "key"};

    record Event(String type, List<Object> args) {}

    static final class Timer {
        final long interval;
        long next;
        final Object fn;
        final boolean repeat;
        Timer(long interval, long next, Object fn, boolean repeat) { this.interval = interval; this.next = next; this.fn = fn; this.repeat = repeat; }
    }

    final MinecraftServer server;
    final UUID playerId;
    private final String code;
    private final Thread thread;
    private volatile boolean stopped = false;
    private volatile boolean finished = false;
    private final AtomicInteger pendingAsync = new AtomicInteger();
    private final Object tickLock = new Object();
    volatile long tick = 0;
    private final ConcurrentLinkedQueue<ConsolePacket.Line> output = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>(10_000);
    final Map<String, List<Object>> handlers = new ConcurrentHashMap<>();
    final List<Timer> timers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private int handlerDepth = 0;
    private final Interpreter interp;
    final WorldApi api;
    BlockPos lastPlayerBlock;
    private int droppedLines = 0;

    public ScriptRunner(MinecraftServer server, ServerPlayer player, String code) {
        this.server = server;
        this.playerId = player.getUUID();
        this.code = code;
        this.lastPlayerBlock = player.blockPosition();
        this.interp = new Interpreter(this);
        this.api = new WorldApi(this, interp);
        this.thread = new Thread(null, this::runThread, "CodingWorld-" + player.getGameProfile().getName(), 64L * 1024 * 1024);
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        stopped = true;
        thread.interrupt();
        synchronized (tickLock) {
            tickLock.notifyAll();
        }
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isStopped() {
        return stopped;
    }

    // ---------- thread body ----------
    private void runThread() {
        long started = System.currentTimeMillis();
        system("▶ Программа запущена");
        try {
            interp.run(code);
            interp.flush();
            collectNamedHandlers();
            if (hasListeners()) {
                system("Программа слушает события (" + String.join(", ", listenerNames()) + "). Нажмите ■ Стоп, чтобы остановить.");
                CodingServer.sendStatus(this, true);
                eventLoop();
            }
            system(String.format(java.util.Locale.ROOT, "✔ Готово за %.1f с", (System.currentTimeMillis() - started) / 1000.0));
        } catch (PyError e) {
            interp.flush();
            error(e);
        } catch (Interpreter.ScriptStopped s) {
            interp.flush();
            system(s.normal ? "✔ Программа завершена" : "■ Программа остановлена");
        } catch (StackOverflowError e) {
            interp.flush();
            error(new PyError("RecursionError", "слишком глубокая рекурсия"));
        } catch (Throwable t) {
            interp.flush();
            CodingWorldMod.LOGGER.error("Script crashed", t);
            print("Внутренняя ошибка мода: " + t, true);
        } finally {
            finished = true;
            CodingServer.onFinished(this);
        }
    }

    private void error(PyError e) {
        output.add(new ConsolePacket.Line(e.pretty(), ConsolePacket.ERROR, e.line));
    }

    private List<String> listenerNames() {
        List<String> names = new ArrayList<>();
        for (var e : handlers.entrySet()) if (!e.getValue().isEmpty()) names.add(e.getKey());
        if (!timers.isEmpty()) names.add("таймеры");
        return names;
    }

    boolean hasListeners() {
        for (List<Object> l : handlers.values()) if (!l.isEmpty()) return true;
        return !timers.isEmpty();
    }

    boolean wants(String event) {
        List<Object> l = handlers.get(event);
        return l != null && !l.isEmpty();
    }

    boolean wantsTick() {
        return wants("tick") || !timers.isEmpty();
    }

    /** Functions named on_tick, on_chat... are handlers automatically. */
    private void collectNamedHandlers() {
        for (String ev : EVENT_NAMES) {
            Object fn = interp.globals.vars().get("on_" + ev);
            if (fn instanceof Values.PyCallable) {
                List<Object> l = handlers.computeIfAbsent(ev, k -> new CopyOnWriteArrayList<>());
                if (!l.contains(fn)) l.add(fn);
            }
        }
    }

    public void addHandler(String event, Object fn) {
        handlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(fn);
        CodingServer.sendStatus(this, true);
    }

    private void eventLoop() throws InterruptedException {
        while (!stopped && hasListeners()) {
            Event ev = events.poll(100, TimeUnit.MILLISECONDS);
            checkAlive();
            if (ev != null) dispatch(ev);
        }
    }

    private void dispatch(Event ev) {
        handlerDepth++;
        try {
            if (ev.type.equals("tick")) {
                tickQueued.set(false);
                for (Timer t : timers) {
                    if (tick >= t.next) {
                        if (t.repeat) t.next = tick + t.interval;
                        else timers.remove(t);
                        call(t.fn, List.of());
                    }
                }
            }
            List<Object> l = handlers.get(ev.type);
            if (l != null) for (Object fn : l) call(fn, ev.args);
        } finally {
            handlerDepth--;
            interp.flush();
        }
    }

    private void call(Object fn, List<Object> args) {
        List<Object> a = args;
        if (fn instanceof Values.PyFunction f && !f.hasVarArgs() && f.paramCount() < args.size()) a = args.subList(0, f.paramCount());
        interp.callValue(fn, a);
    }

    void offer(String type, Object... args) {
        if (stopped || finished || !wants(type)) return;
        events.offer(new Event(type, List.of(args)));
    }

    // ---------- called on the server thread ----------
    void onServerTick() {
        tick++;
        synchronized (tickLock) {
            tickLock.notifyAll();
        }
        if (wantsTick() && tickQueued.compareAndSet(false, true)) events.offer(new Event("tick", List.of()));
        if (wants("player_move")) {
            ServerPlayer p = server.getPlayerList().getPlayer(playerId);
            if (p != null) {
                BlockPos pos = p.blockPosition();
                if (!pos.equals(lastPlayerBlock)) {
                    lastPlayerBlock = pos;
                    offer("player_move", (long) pos.getX(), (long) pos.getY(), (long) pos.getZ());
                }
            }
        }
        flushOutput();
    }

    void flushOutput() {
        if (output.isEmpty()) return;
        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
        List<ConsolePacket.Line> lines = new ArrayList<>();
        ConsolePacket.Line l;
        while (lines.size() < 200 && (l = output.poll()) != null) lines.add(l);
        if (output.size() > 2000) {
            droppedLines += output.size();
            output.clear();
        }
        if (droppedLines > 0 && output.isEmpty()) {
            lines.add(new ConsolePacket.Line("... пропущено строк: " + droppedLines + " (слишком много print)", ConsolePacket.SYSTEM, 0));
            droppedLines = 0;
        }
        if (p != null) Network.toPlayer(p, new ConsolePacket(lines));
    }

    // ---------- Interpreter.Host ----------
    @Override
    public void print(String line, boolean error) {
        if (output.size() > 5000) {
            droppedLines++;
            return;
        }
        if (line.length() > 2000) line = line.substring(0, 2000) + "…";
        output.add(new ConsolePacket.Line(line, error ? ConsolePacket.WARN : ConsolePacket.NORMAL, 0));
    }

    void system(String line) {
        output.add(new ConsolePacket.Line(line, ConsolePacket.SYSTEM, 0));
    }

    @Override
    public void checkAlive() {
        if (stopped) throw new Interpreter.ScriptStopped(false);
    }

    @Override
    public void sleepTicks(long ticks) {
        interp.flush();
        collectNamedHandlers();
        long target = tick + Math.max(0, ticks);
        if (ticks <= 0 && handlerDepth == 0) {
            Event ev;
            while ((ev = events.poll()) != null) dispatch(ev);
        }
        while (tick < target) {
            checkAlive();
            if (handlerDepth == 0) {
                Event ev;
                while ((ev = events.poll()) != null) {
                    dispatch(ev);
                    checkAlive();
                }
            }
            synchronized (tickLock) {
                if (tick >= target || stopped) break;
                try {
                    tickLock.wait(100);
                } catch (InterruptedException e) {
                    throw new Interpreter.ScriptStopped(false);
                }
            }
        }
        checkAlive();
    }

    @Override
    public Object dynamic(String name) {
        return api.variable(name);
    }

    @Override
    public Collection<String> dynamicNames() {
        List<String> names = new ArrayList<>();
        for (ApiDocs.Var v : ApiDocs.VARIABLES) names.add(v.name());
        return names;
    }

    // ---------- server thread access ----------
    /** Runs the task on the server thread and waits for its result. */
    <T> T sync(Supplier<T> task) {
        checkAlive();
        if (server.isSameThread()) return task.get();
        CompletableFuture<T> f = server.submit(task);
        while (true) {
            try {
                return f.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (stopped) {
                    f.cancel(false);
                    throw new Interpreter.ScriptStopped(false);
                }
                if (!server.isRunning()) throw new Interpreter.ScriptStopped(false);
            } catch (InterruptedException e) {
                throw new Interpreter.ScriptStopped(false);
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof PyError pe) throw pe;
                if (c instanceof Interpreter.ScriptStopped s) throw s;
                CodingWorldMod.LOGGER.warn("World call failed", c);
                throw new PyError("WorldError", String.valueOf(c));
            }
        }
    }

    /** Queues a world change without waiting for it; order with sync() calls is kept. */
    void async(Runnable task) {
        checkAlive();
        if (pendingAsync.incrementAndGet() > 20_000) {
            while (pendingAsync.get() > 2_000) {
                checkAlive();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    throw new Interpreter.ScriptStopped(false);
                }
            }
        }
        server.execute(() -> {
            try {
                if (!stopped) task.run();
            } catch (PyError e) {
                error(e);
                stop();
            } catch (Exception e) {
                print("Ошибка в мире: " + e, true);
            } finally {
                pendingAsync.decrementAndGet();
            }
        });
    }

    ServerPlayer player() {
        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
        if (p == null) throw new Interpreter.ScriptStopped(false);
        return p;
    }

    void clearConsole() {
        interp.flush();
        output.clear();
        output.add(new ConsolePacket.Line("", ConsolePacket.CLEAR, 0));
    }

    static String str(Object o) {
        return Py.str(o);
    }
}
