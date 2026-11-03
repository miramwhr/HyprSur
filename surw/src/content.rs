//! Things that can be on screen: a picture, a video, a schedule of pictures.

use crate::frame::Frame;
use crate::spec::{unix_to_system, Source, TransitionSpec, Wallpaper};
use crate::transition::{compose, quantize, resolve_random, step_interval};
use crate::{dynamic, slideshow, video};
use anyhow::Result;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

pub trait Content: Send {
    /// Brings the content up to `now`. Returns true when `frame()` changed.
    fn update(&mut self, now: SystemTime) -> bool;
    fn frame(&self) -> &Frame;
    /// When `update` should be called next; `None` means never (static picture).
    fn next_wake(&self, now: SystemTime) -> Option<SystemTime>;
    /// Moves the content's timeline forward (used after a pause).
    fn shift(&mut self, _by: Duration) {}
}

pub struct Still(pub Frame);

impl Content for Still {
    fn update(&mut self, _now: SystemTime) -> bool {
        false
    }
    fn frame(&self) -> &Frame {
        &self.0
    }
    fn next_wake(&self, _now: SystemTime) -> Option<SystemTime> {
        None
    }
}

pub fn build(wp: &Wallpaper, w: u32, h: u32, now: SystemTime) -> Result<Box<dyn Content>> {
    Ok(match &wp.source {
        Source::Color { rgb } => Box::new(Still(Frame::solid(w, h, *rgb))),
        Source::Image { path } => Box::new(Still(Frame::load(path, w, h, wp.resize, wp.fill)?)),
        Source::Video { path, speed, duration, sync_clock, looping, interpolate, max_fps } => {
            let cfg = video::VideoCfg::new(path, w, h, wp.resize, wp.fill, *speed, *duration, *looping, *interpolate, *max_fps)?;
            let origin = if *sync_clock {
                unix_to_system(crate::sun::local_midnight(crate::sun::today()))
            } else {
                unix_to_system(wp.started_at)
            };
            let mut v = video::VideoContent::new(cfg, origin, now)?;
            v.clock_locked = *sync_clock;
            Box::new(v)
        }
        Source::Dynamic { path, latitude, longitude } => {
            let d = dynamic::Dynamic::load(path, *latitude, *longitude)?;
            let spec = d.transition.clone();
            let paths = d.images.clone();
            Box::new(Scheduled::new(Box::new(d), paths, spec, w, h, wp))
        }
        Source::Slideshow { paths, every, shuffle, align, transition } => {
            let s = slideshow::Slideshow::new(paths.len(), *every, *shuffle, *align, transition.duration, wp.started_at);
            Box::new(Scheduled::new(Box::new(s), paths.clone(), transition.clone(), w, h, wp))
        }
    })
}

/// What a schedule wants on screen at some moment.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Moment {
    pub from: usize,
    pub to: usize,
    /// transition window; outside it `to` is shown
    pub start: SystemTime,
    pub end: SystemTime,
    /// when the following transition begins
    pub next_start: SystemTime,
}

pub trait Schedule: Send {
    fn at(&mut self, now: SystemTime) -> Moment;
    fn shift(&mut self, _by: Duration) {}
}

/// Shows a list of pictures following a [`Schedule`], with transitions between them.
/// Only the two pictures that are needed right now are kept in memory.
pub struct Scheduled {
    schedule: Box<dyn Schedule>,
    paths: Vec<PathBuf>,
    spec: TransitionSpec,
    w: u32,
    h: u32,
    resize: crate::spec::ResizeMode,
    fill: [u8; 3],
    cache: Vec<(usize, Arc<Frame>)>,
    out: Frame,
    last: Option<(usize, usize, i64)>,
    moment: Option<Moment>,
}

impl Scheduled {
    pub fn new(schedule: Box<dyn Schedule>, paths: Vec<PathBuf>, spec: TransitionSpec, w: u32, h: u32, wp: &Wallpaper) -> Self {
        Scheduled {
            schedule,
            paths,
            spec,
            w,
            h,
            resize: wp.resize,
            fill: wp.fill,
            cache: Vec::new(),
            out: Frame::solid(w, h, wp.fill),
            last: None,
            moment: None,
        }
    }

    fn picture(&mut self, idx: usize) -> Arc<Frame> {
        if let Some((_, f)) = self.cache.iter().find(|(i, _)| *i == idx) {
            return f.clone();
        }
        let f = match Frame::load(&self.paths[idx], self.w, self.h, self.resize, self.fill) {
            Ok(f) => Arc::new(f),
            Err(e) => {
                eprintln!("surw: {e:#}");
                Arc::new(Frame::solid(self.w, self.h, self.fill))
            }
        };
        if self.cache.len() >= 3 {
            self.cache.remove(0);
        }
        self.cache.push((idx, f.clone()));
        f
    }

    fn window_spec(&self, m: &Moment) -> TransitionSpec {
        let mut spec = self.spec.clone();
        spec.duration = secs_between(m.start, m.end);
        spec.kind = resolve_random(spec.kind, crate::spec::system_to_unix(m.start) as u64);
        spec
    }
}

pub fn secs_between(a: SystemTime, b: SystemTime) -> f64 {
    b.duration_since(a).map(|d| d.as_secs_f64()).unwrap_or(0.0)
}

impl Content for Scheduled {
    fn update(&mut self, now: SystemTime) -> bool {
        let m = self.schedule.at(now);
        self.moment = Some(m);
        let spec = self.window_spec(&m);
        let in_window = m.from != m.to && now >= m.start && now < m.end;
        let (from, to, level) = if in_window {
            let t = spec.easing.apply(secs_between(m.start, now) / spec.duration.max(1e-9));
            let q = quantize(t, spec.kind, self.w, self.h);
            (m.from, m.to, (q * 1e6) as i64)
        } else {
            (m.to, m.to, 1_000_000)
        };
        if self.last == Some((from, to, level)) {
            return false;
        }
        self.last = Some((from, to, level));
        let b = self.picture(to);
        if from == to || level >= 1_000_000 {
            self.out.data.copy_from_slice(&b.data);
        } else {
            let a = self.picture(from);
            compose(spec.kind, &spec, &a.data, &b.data, &mut self.out.data, self.w, self.h, level as f64 / 1e6);
        }
        true
    }

    fn frame(&self) -> &Frame {
        &self.out
    }

    fn next_wake(&self, now: SystemTime) -> Option<SystemTime> {
        let m = self.moment?;
        if m.from != m.to && now < m.end {
            let spec = self.window_spec(&m);
            let t = (now.max(m.start)) + step_interval(&spec, self.w, self.h);
            Some(t.min(m.end))
        } else {
            Some(m.next_start.max(now + Duration::from_millis(1)))
        }
    }

    fn shift(&mut self, by: Duration) {
        self.schedule.shift(by);
    }
}
