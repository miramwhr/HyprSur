//! The Wayland side: one background layer surface per monitor, the IPC socket and the
//! saved state.

use crate::ipc::{socket_path, OutputInfo, Request, Response};
use crate::scene::{self, FrameMsg, SetCmd, Worker, WorkerCmd};
use crate::spec::{now_unix, TransitionSpec, Wallpaper};
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use smithay_client_toolkit::{
    compositor::{CompositorHandler, CompositorState},
    delegate_compositor, delegate_layer, delegate_output, delegate_registry, delegate_shm,
    output::{OutputHandler, OutputState},
    reexports::{
        calloop::{
            channel::{channel, Event as ChanEvent, Sender},
            EventLoop,
        },
        calloop_wayland_source::WaylandSource,
    },
    registry::{ProvidesRegistryState, RegistryState},
    registry_handlers,
    shell::{
        wlr_layer::{Anchor, KeyboardInteractivity, Layer, LayerShell, LayerShellHandler, LayerSurface, LayerSurfaceConfigure},
        WaylandSurface,
    },
    shm::{slot::SlotPool, Shm, ShmHandler},
};
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::PathBuf;
use std::sync::mpsc;
use smithay_client_toolkit::reexports::client::{
    globals::registry_queue_init,
    protocol::{wl_output, wl_shm, wl_surface},
    Connection, QueueHandle,
};

/// What one monitor shows, as saved to disk.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Record {
    pub wallpaper: Wallpaper,
    pub previous: Option<Wallpaper>,
    pub transition: TransitionSpec,
    pub transition_start: f64,
}

impl Record {
    fn to_cmd(&self, restoring: bool) -> SetCmd {
        SetCmd {
            wallpaper: self.wallpaper.clone(),
            transition: self.transition.clone(),
            start: restoring.then_some(self.transition_start),
            previous: self.previous.clone(),
        }
    }

    fn transition_running(&self) -> bool {
        self.previous.is_some() && now_unix() < self.transition_start + self.transition.duration
    }
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Saved {
    default: Option<Record>,
    outputs: BTreeMap<String, Record>,
}

fn state_path() -> PathBuf {
    let base = std::env::var("XDG_CACHE_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| crate::expand_home("~/.cache"));
    base.join("surw").join("state.json")
}

fn load_state() -> Saved {
    std::fs::read_to_string(state_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_state(s: &Saved) {
    let p = state_path();
    if let Some(d) = p.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let tmp = p.with_extension("json.tmp");
    if let Ok(json) = serde_json::to_string_pretty(s) {
        if std::fs::write(&tmp, json).is_ok() {
            let _ = std::fs::rename(&tmp, &p);
        }
    }
}

struct Slot {
    id: u32,
    output: wl_output::WlOutput,
    name: String,
    layer: LayerSurface,
    width: u32,
    height: u32,
    scale: i32,
    configured: bool,
    waiting: bool,
    pending: Option<crate::frame::Frame>,
    paused: bool,
    record: Record,
    worker: Worker,
}

impl Slot {
    fn buffer_size(&self) -> (u32, u32) {
        (self.width * self.scale as u32, self.height * self.scale as u32)
    }
}

type IpcMsg = (Request, mpsc::Sender<Response>);

struct Daemon {
    registry_state: RegistryState,
    output_state: OutputState,
    compositor: CompositorState,
    layer_shell: LayerShell,
    shm: Shm,
    pool: SlotPool,
    slots: Vec<Slot>,
    next_id: u32,
    saved: Saved,
    restore: bool,
    frames_tx: Sender<FrameMsg>,
    qh: QueueHandle<Daemon>,
    exit: bool,
}

pub fn run(no_restore: bool) -> Result<()> {
    let sock = socket_path();
    if UnixStream::connect(&sock).is_ok() {
        anyhow::bail!("a surw daemon is already running ({})", sock.display());
    }
    let _ = std::fs::remove_file(&sock);
    let listener = UnixListener::bind(&sock).with_context(|| format!("cannot create socket {}", sock.display()))?;

    let conn = Connection::connect_to_env().context("cannot connect to the Wayland compositor")?;
    let (globals, event_queue) = registry_queue_init::<Daemon>(&conn)?;
    let qh = event_queue.handle();
    let mut event_loop: EventLoop<Daemon> = EventLoop::try_new()?;
    WaylandSource::new(conn.clone(), event_queue)
        .insert(event_loop.handle())
        .map_err(|e| anyhow::anyhow!("{e}"))?;

    let (frames_tx, frames_rx) = channel::<FrameMsg>();
    event_loop
        .handle()
        .insert_source(frames_rx, |ev, _, d: &mut Daemon| {
            if let ChanEvent::Msg(m) = ev {
                d.on_frame(m);
            }
        })
        .map_err(|e| anyhow::anyhow!("{e}"))?;

    let (ipc_tx, ipc_rx) = channel::<IpcMsg>();
    event_loop
        .handle()
        .insert_source(ipc_rx, |ev, _, d: &mut Daemon| {
            if let ChanEvent::Msg((req, reply)) = ev {
                let resp = d.on_request(req);
                let _ = reply.send(resp);
            }
        })
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    std::thread::Builder::new().name("surw-ipc".into()).spawn(move || serve(listener, ipc_tx))?;

    let shm = Shm::bind(&globals, &qh).context("wl_shm is not available")?;
    let pool = SlotPool::new(1920 * 1080 * 4, &shm)?;
    let mut daemon = Daemon {
        registry_state: RegistryState::new(&globals),
        output_state: OutputState::new(&globals, &qh),
        compositor: CompositorState::bind(&globals, &qh).context("wl_compositor is not available")?,
        layer_shell: LayerShell::bind(&globals, &qh)
            .context("the compositor does not support wlr-layer-shell (Hyprland, Sway, river, niri and KDE do)")?,
        shm,
        pool,
        slots: Vec::new(),
        next_id: 0,
        saved: if no_restore { Saved::default() } else { load_state() },
        restore: !no_restore,
        frames_tx,
        qh: qh.clone(),
        exit: false,
    };
    eprintln!("surw: daemon listening on {}", sock.display());

    while !daemon.exit {
        event_loop.dispatch(None, &mut daemon)?;
    }
    let _ = std::fs::remove_file(&sock);
    Ok(())
}

fn serve(listener: UnixListener, tx: Sender<IpcMsg>) {
    for stream in listener.incoming().flatten() {
        let tx = tx.clone();
        std::thread::spawn(move || {
            let mut reader = BufReader::new(match stream.try_clone() {
                Ok(s) => s,
                Err(_) => return,
            });
            let mut line = String::new();
            if reader.read_line(&mut line).is_err() {
                return;
            }
            let resp = match serde_json::from_str::<Request>(&line) {
                Ok(req) => {
                    let (rtx, rrx) = mpsc::channel();
                    if tx.send((req, rtx)).is_err() {
                        return;
                    }
                    rrx.recv().unwrap_or(Response::Error { message: "daemon is shutting down".into() })
                }
                Err(e) => Response::Error { message: format!("bad request: {e}") },
            };
            let mut stream = stream;
            if let Ok(mut s) = serde_json::to_string(&resp) {
                s.push('\n');
                let _ = stream.write_all(s.as_bytes());
            }
        });
    }
}

impl Daemon {
    fn slot_by_surface(&mut self, surface: &wl_surface::WlSurface) -> Option<&mut Slot> {
        self.slots.iter_mut().find(|s| s.layer.wl_surface() == surface)
    }

    fn on_frame(&mut self, msg: FrameMsg) {
        let Some(i) = self.slots.iter().position(|s| s.id == msg.id) else { return };
        if !self.slots[i].configured || (msg.frame.w, msg.frame.h) != self.slots[i].buffer_size() {
            self.slots[i].worker.pool.lock().unwrap().push(msg.frame.data);
            return;
        }
        if self.slots[i].waiting {
            // The compositor has not shown the previous frame yet; keep only the newest one.
            if let Some(old) = self.slots[i].pending.replace(msg.frame) {
                self.slots[i].worker.pool.lock().unwrap().push(old.data);
            }
            return;
        }
        self.draw(i, msg.frame);
    }

    fn draw(&mut self, i: usize, frame: crate::frame::Frame) {
        let slot = &mut self.slots[i];
        let (w, h) = (frame.w as i32, frame.h as i32);
        match self.pool.create_buffer(w, h, w * 4, wl_shm::Format::Xrgb8888) {
            Ok((buffer, canvas)) => {
                canvas[..frame.data.len()].copy_from_slice(&frame.data);
                let surface = slot.layer.wl_surface();
                surface.set_buffer_scale(slot.scale);
                surface.damage_buffer(0, 0, w, h);
                surface.frame(&self.qh, surface.clone());
                if buffer.attach_to(surface).is_ok() {
                    slot.waiting = true;
                }
                slot.layer.commit();
            }
            Err(e) => eprintln!("surw: cannot allocate a buffer: {e}"),
        }
        slot.worker.pool.lock().unwrap().push(frame.data);
    }

    fn record_for(&self, name: &str) -> Record {
        self.saved
            .outputs
            .get(name)
            .or(self.saved.default.as_ref())
            .cloned()
            .unwrap_or(Record {
                wallpaper: Wallpaper::black(),
                previous: None,
                transition: TransitionSpec::instant(),
                transition_start: 0.0,
            })
    }

    fn matches(outputs: &[String], name: &str) -> bool {
        outputs.is_empty() || outputs.iter().any(|o| o == name)
    }

    fn on_request(&mut self, req: Request) -> Response {
        match req {
            Request::Set { outputs, wallpaper, transition } => {
                let unknown: Vec<&String> =
                    outputs.iter().filter(|o| !self.slots.iter().any(|s| &s.name == *o)).collect();
                if !outputs.is_empty() && unknown.len() == outputs.len() {
                    let names: Vec<&str> = self.slots.iter().map(|s| s.name.as_str()).collect();
                    return Response::Error {
                        message: format!("no such monitor: {:?}; connected: {}", unknown, names.join(", ")),
                    };
                }
                let now = now_unix();
                for slot in self.slots.iter_mut().filter(|s| Self::matches(&outputs, &s.name)) {
                    let rec = Record {
                        wallpaper: wallpaper.clone(),
                        previous: Some(slot.record.wallpaper.clone()),
                        transition: transition.clone(),
                        transition_start: now,
                    };
                    slot.worker.send(WorkerCmd::Set(rec.to_cmd(false)));
                    self.saved.outputs.insert(slot.name.clone(), rec.clone());
                    slot.record = rec;
                }
                if outputs.is_empty() {
                    // monitors that are unplugged right now get the new wallpaper when they come back
                    let connected: Vec<String> = self.slots.iter().map(|s| s.name.clone()).collect();
                    self.saved.outputs.retain(|name, _| connected.contains(name));
                    self.saved.default = Some(Record {
                        wallpaper: wallpaper.clone(),
                        previous: None,
                        transition: TransitionSpec::instant(),
                        transition_start: now,
                    });
                }
                save_state(&self.saved);
                let msg = (!unknown.is_empty()).then(|| format!("skipped unknown monitors: {unknown:?}"));
                Response::Ok { message: msg }
            }
            Request::Pause { outputs } => {
                for s in self.slots.iter_mut().filter(|s| Self::matches(&outputs, &s.name)) {
                    s.paused = true;
                    s.worker.send(WorkerCmd::Pause);
                }
                Response::Ok { message: None }
            }
            Request::Resume { outputs } => {
                for s in self.slots.iter_mut().filter(|s| Self::matches(&outputs, &s.name)) {
                    s.paused = false;
                    s.worker.send(WorkerCmd::Resume);
                }
                Response::Ok { message: None }
            }
            Request::Query => Response::Info {
                outputs: self
                    .slots
                    .iter()
                    .map(|s| OutputInfo {
                        name: s.name.clone(),
                        width: s.width,
                        height: s.height,
                        scale: s.scale,
                        wallpaper: s.record.wallpaper.describe(),
                        transition: s.record.transition_running().then(|| {
                            let left = s.record.transition_start + s.record.transition.duration - now_unix();
                            format!(
                                "{:?} from {}, {} left",
                                s.record.transition.kind,
                                s.record.previous.as_ref().map(|p| p.describe()).unwrap_or_default(),
                                crate::duration::format_duration(left.max(0.0))
                            )
                            .to_lowercase()
                        }),
                        paused: s.paused,
                    })
                    .collect(),
            },
            Request::Kill => {
                self.exit = true;
                Response::Ok { message: None }
            }
        }
    }
}

impl CompositorHandler for Daemon {
    fn scale_factor_changed(&mut self, _: &Connection, _: &QueueHandle<Self>, surface: &wl_surface::WlSurface, factor: i32) {
        if let Some(slot) = self.slot_by_surface(surface) {
            if slot.scale != factor && factor > 0 {
                slot.scale = factor;
                let (w, h) = slot.buffer_size();
                slot.worker.send(WorkerCmd::Resize(w, h));
            }
        }
    }

    fn transform_changed(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &wl_surface::WlSurface, _: wl_output::Transform) {}

    fn frame(&mut self, _: &Connection, _: &QueueHandle<Self>, surface: &wl_surface::WlSurface, _: u32) {
        let Some(i) = self.slots.iter().position(|s| s.layer.wl_surface() == surface) else { return };
        self.slots[i].waiting = false;
        if let Some(f) = self.slots[i].pending.take() {
            self.draw(i, f);
        }
    }

    fn surface_enter(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &wl_surface::WlSurface, _: &wl_output::WlOutput) {}
    fn surface_leave(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &wl_surface::WlSurface, _: &wl_output::WlOutput) {}
}

impl OutputHandler for Daemon {
    fn output_state(&mut self) -> &mut OutputState {
        &mut self.output_state
    }

    fn new_output(&mut self, _: &Connection, qh: &QueueHandle<Self>, output: wl_output::WlOutput) {
        let info = self.output_state.info(&output);
        let id = self.next_id;
        self.next_id += 1;
        let name = info.as_ref().and_then(|i| i.name.clone()).unwrap_or_else(|| format!("output-{id}"));
        let scale = info.as_ref().map(|i| i.scale_factor).unwrap_or(1).max(1);
        let surface = self.compositor.create_surface(qh);
        let layer = self.layer_shell.create_layer_surface(qh, surface, Layer::Background, Some("surw-wallpaper"), Some(&output));
        layer.set_anchor(Anchor::TOP | Anchor::BOTTOM | Anchor::LEFT | Anchor::RIGHT);
        layer.set_exclusive_zone(-1);
        layer.set_keyboard_interactivity(KeyboardInteractivity::None);
        layer.set_size(0, 0);
        // wallpapers never take input
        if let Ok(region) = smithay_client_toolkit::compositor::Region::new(&self.compositor) {
            layer.wl_surface().set_input_region(Some(region.wl_region()));
        }
        layer.commit();

        let record = self.record_for(&name);
        let restoring = self.restore && record.transition_running();
        let mut cmd = record.to_cmd(restoring);
        if !restoring {
            cmd.transition = TransitionSpec::instant();
        }
        let worker = scene::spawn(id, name.clone(), cmd, self.frames_tx.clone());
        eprintln!("surw: monitor {name}: {}", record.wallpaper.describe());
        self.slots.push(Slot {
            id,
            output,
            name,
            layer,
            width: 0,
            height: 0,
            scale,
            configured: false,
            waiting: false,
            pending: None,
            paused: false,
            record,
            worker,
        });
    }

    fn update_output(&mut self, _: &Connection, _: &QueueHandle<Self>, output: wl_output::WlOutput) {
        let Some(info) = self.output_state.info(&output) else { return };
        if let Some(slot) = self.slots.iter_mut().find(|s| s.output == output) {
            if let Some(n) = info.name {
                slot.name = n;
            }
            let scale = info.scale_factor.max(1);
            if scale != slot.scale {
                slot.scale = scale;
                if slot.configured {
                    let (w, h) = slot.buffer_size();
                    slot.worker.send(WorkerCmd::Resize(w, h));
                }
            }
        }
    }

    fn output_destroyed(&mut self, _: &Connection, _: &QueueHandle<Self>, output: wl_output::WlOutput) {
        self.slots.retain(|s| s.output != output);
    }
}

impl LayerShellHandler for Daemon {
    fn closed(&mut self, _: &Connection, _: &QueueHandle<Self>, layer: &LayerSurface) {
        self.slots.retain(|s| &s.layer != layer);
    }

    fn configure(&mut self, _: &Connection, _: &QueueHandle<Self>, layer: &LayerSurface, configure: LayerSurfaceConfigure, _: u32) {
        let Some(i) = self.slots.iter().position(|s| &s.layer == layer) else { return };
        let (mut w, mut h) = configure.new_size;
        if w == 0 || h == 0 {
            if let Some((lw, lh)) = self.output_state.info(&self.slots[i].output).and_then(|i| i.logical_size) {
                w = lw as u32;
                h = lh as u32;
            }
        }
        let slot = &mut self.slots[i];
        let first = !slot.configured;
        slot.width = w.max(1);
        slot.height = h.max(1);
        slot.configured = true;
        let (bw, bh) = slot.buffer_size();
        slot.worker.send(WorkerCmd::Resize(bw, bh));
        if first {
            // Map the surface right away with a black picture until the worker is ready.
            let black = crate::frame::Frame::solid(bw, bh, [0, 0, 0]);
            self.draw(i, black);
        }
    }
}

impl ShmHandler for Daemon {
    fn shm_state(&mut self) -> &mut Shm {
        &mut self.shm
    }
}

impl ProvidesRegistryState for Daemon {
    fn registry(&mut self) -> &mut RegistryState {
        &mut self.registry_state
    }
    registry_handlers![OutputState];
}

delegate_compositor!(Daemon);
delegate_output!(Daemon);
delegate_shm!(Daemon);
delegate_layer!(Daemon);
delegate_registry!(Daemon);
