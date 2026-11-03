//! One worker thread per monitor. It owns what is on that monitor, runs the animation
//! clock and hands finished frames to the Wayland thread.

use crate::content::{build, Content, Still};
use crate::frame::Frame;
use crate::spec::{unix_to_system, TransitionKind, TransitionSpec, Wallpaper};
use crate::transition::{compose, progress, quantize, resolve_random, step_interval};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

pub struct FrameMsg {
    pub id: u32,
    pub frame: Frame,
}

#[derive(Clone)]
pub struct SetCmd {
    pub wallpaper: Wallpaper,
    pub transition: TransitionSpec,
    /// unix time; `Some` when restoring a transition that was already running
    pub start: Option<f64>,
    /// what was on screen before, used to rebuild a running transition after a restart
    pub previous: Option<Wallpaper>,
}

pub enum WorkerCmd {
    Resize(u32, u32),
    Set(SetCmd),
    Pause,
    Resume,
    Quit,
}

pub type BufPool = Arc<Mutex<Vec<Vec<u8>>>>;

pub struct Worker {
    tx: Sender<WorkerCmd>,
    pub pool: BufPool,
}

impl Worker {
    pub fn send(&self, cmd: WorkerCmd) {
        let _ = self.tx.send(cmd);
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.tx.send(WorkerCmd::Quit);
    }
}

struct Transition {
    spec: TransitionSpec,
    start: SystemTime,
}

struct Scene {
    w: u32,
    h: u32,
    set: SetCmd,
    current: Box<dyn Content>,
    from: Option<Box<dyn Content>>,
    trans: Option<Transition>,
    level: i64,
    dirty: bool,
}

impl Scene {
    fn transitioning(&self) -> bool {
        self.trans.is_some() && self.from.is_some()
    }

    fn update(&mut self, now: SystemTime) -> bool {
        let c = self.current.update(now);
        let f = match &mut self.from {
            Some(f) if self.trans.is_some() => f.update(now),
            _ => false,
        };
        if let Some(tr) = &self.trans {
            let t = progress(&tr.spec, tr.start, now);
            if t >= 1.0 || self.from.is_none() {
                self.trans = None;
                self.from = None;
                self.level = -1;
                return true;
            }
            let level = (quantize(t, tr.spec.kind, self.w, self.h) * 1e6) as i64;
            if level != self.level || c || f || self.dirty {
                self.level = level;
                self.dirty = false;
                return true;
            }
            return false;
        }
        let r = c || self.dirty;
        self.dirty = false;
        r
    }

    fn render(&self, out: &mut [u8]) {
        match (&self.trans, &self.from) {
            (Some(tr), Some(from)) => compose(
                tr.spec.kind,
                &tr.spec,
                &from.frame().data,
                &self.current.frame().data,
                out,
                self.w,
                self.h,
                self.level.max(0) as f64 / 1e6,
            ),
            _ => out.copy_from_slice(&self.current.frame().data),
        }
    }

    fn next_wake(&self, now: SystemTime) -> Option<SystemTime> {
        let mut wake = self.current.next_wake(now);
        let mut consider = |t: Option<SystemTime>| {
            if let Some(t) = t {
                wake = Some(wake.map_or(t, |w| w.min(t)));
            }
        };
        if let (Some(tr), Some(from)) = (&self.trans, &self.from) {
            consider(from.next_wake(now));
            let end = tr.start + Duration::from_secs_f64(tr.spec.duration.max(0.0));
            let step = now.max(tr.start) + step_interval(&tr.spec, self.w, self.h);
            consider(Some(step.min(end)));
        }
        wake
    }

    fn shift(&mut self, by: Duration) {
        self.current.shift(by);
        if let Some(f) = &mut self.from {
            f.shift(by);
        }
        if let Some(tr) = &mut self.trans {
            tr.start += by;
        }
    }
}

pub fn spawn(id: u32, name: String, initial: SetCmd, frames: smithay_client_toolkit::reexports::calloop::channel::Sender<FrameMsg>) -> Worker {
    let (tx, rx) = channel();
    let pool: BufPool = Arc::new(Mutex::new(Vec::new()));
    let pool2 = pool.clone();
    std::thread::Builder::new()
        .name(format!("surw-{name}"))
        .spawn(move || run(id, name, initial, rx, frames, pool2))
        .expect("spawn worker thread");
    Worker { tx, pool }
}

fn build_scene(name: &str, set: SetCmd, w: u32, h: u32, old: Option<Scene>) -> Option<Scene> {
    let now = SystemTime::now();
    let current = match build(&set.wallpaper, w, h, now) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("surw [{name}]: {e:#}");
            return old;
        }
    };
    let mut spec = set.transition.clone();
    let start = set.start.map(unix_to_system).unwrap_or_else(SystemTime::now);
    spec.kind = resolve_random(spec.kind, crate::spec::system_to_unix(start) as u64);
    let wants_transition = spec.kind != TransitionKind::None && spec.duration > 0.0 && progress(&spec, start, now) < 1.0;

    let from: Option<Box<dyn Content>> = if !wants_transition {
        None
    } else {
        match old {
            // something is already on screen: keep it moving under the new one
            Some(old) if old.w == w && old.h == h => {
                if old.transitioning() {
                    let mut snap = Frame::solid(w, h, [0, 0, 0]);
                    old.render(&mut snap.data);
                    Some(Box::new(Still(snap)))
                } else {
                    Some(old.current)
                }
            }
            // restoring after a restart / resize: rebuild the previous picture
            _ => set.previous.as_ref().and_then(|p| build(p, w, h, now).ok()),
        }
    };
    Some(Scene {
        w,
        h,
        trans: from.as_ref().map(|_| Transition { spec, start }),
        from,
        current,
        set,
        level: -1,
        dirty: true,
    })
}

fn run(
    id: u32,
    name: String,
    initial: SetCmd,
    rx: Receiver<WorkerCmd>,
    frames: smithay_client_toolkit::reexports::calloop::channel::Sender<FrameMsg>,
    pool: BufPool,
) {
    let mut size: Option<(u32, u32)> = None;
    let mut pending: Option<SetCmd> = Some(initial);
    let mut scene: Option<Scene> = None;
    let mut paused_at: Option<SystemTime> = None;
    loop {
        let now = SystemTime::now();
        let wake = if paused_at.is_some() { None } else { scene.as_ref().and_then(|s| s.next_wake(now)) };
        let first = match wake {
            None => match rx.recv() {
                Ok(c) => Some(c),
                Err(_) => return,
            },
            Some(t) => {
                let d = t.duration_since(now).unwrap_or(Duration::ZERO);
                match rx.recv_timeout(d) {
                    Ok(c) => Some(c),
                    Err(RecvTimeoutError::Timeout) => None,
                    Err(RecvTimeoutError::Disconnected) => return,
                }
            }
        };
        let mut cmds: Vec<WorkerCmd> = first.into_iter().collect();
        while let Ok(c) = rx.try_recv() {
            cmds.push(c);
        }
        // Several resizes in a row: only the last one matters.
        let last_resize = cmds.iter().rposition(|c| matches!(c, WorkerCmd::Resize(..)));
        for (i, cmd) in cmds.into_iter().enumerate() {
            match cmd {
                WorkerCmd::Quit => return,
                WorkerCmd::Resize(w, h) => {
                    if Some(i) != last_resize || size == Some((w, h)) {
                        continue;
                    }
                    size = Some((w, h));
                    let set = match (&scene, pending.take()) {
                        (_, Some(p)) => p,
                        (Some(s), None) => {
                            // keep a running transition going on the new size
                            let mut set = s.set.clone();
                            if let Some(tr) = &s.trans {
                                set.start = Some(crate::spec::system_to_unix(tr.start));
                            } else {
                                set.transition = TransitionSpec::instant();
                            }
                            set
                        }
                        (None, None) => continue,
                    };
                    scene = build_scene(&name, set, w, h, None);
                }
                WorkerCmd::Set(set) => match size {
                    None => pending = Some(set),
                    Some((w, h)) => scene = build_scene(&name, set, w, h, scene.take()),
                },
                WorkerCmd::Pause => {
                    if paused_at.is_none() {
                        paused_at = Some(SystemTime::now());
                    }
                }
                WorkerCmd::Resume => {
                    if let Some(p) = paused_at.take() {
                        if let Some(s) = &mut scene {
                            s.shift(SystemTime::now().duration_since(p).unwrap_or_default());
                        }
                    }
                }
            }
        }
        if paused_at.is_some() {
            continue;
        }
        let Some(s) = &mut scene else { continue };
        if s.update(SystemTime::now()) {
            let len = (s.w * s.h * 4) as usize;
            let mut buf = pool.lock().unwrap().pop().unwrap_or_default();
            buf.resize(len, 0);
            s.render(&mut buf);
            if frames.send(FrameMsg { id, frame: Frame { w: s.w, h: s.h, data: buf } }).is_err() {
                return;
            }
        }
    }
}
