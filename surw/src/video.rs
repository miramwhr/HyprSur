//! Video wallpapers decoded by an `ffmpeg` child process.
//!
//! The playback position is a pure function of the wall clock:
//! `frame(i of loop k)` is shown at `origin + k * loop_len + i * frame_secs`.
//! That is what lets a 10 second clip be stretched over 24 hours (one frame every few
//! minutes, smoothly blended), squeezed into a few seconds, tied to the time of day like a
//! macOS dynamic wallpaper, and resumed after a suspend or a daemon restart.

use crate::content::Content;
use crate::frame::Frame;
use crate::spec::{Interpolate, ResizeMode};
use crate::transition::fade;
use anyhow::{bail, Context, Result};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{sync_channel, Receiver, RecvTimeoutError};
use std::time::{Duration, SystemTime};

#[derive(Debug, Clone)]
pub struct Probe {
    pub duration: f64,
    pub fps: f64,
}

pub fn probe(path: &Path) -> Result<Probe> {
    let out = Command::new("ffprobe")
        .args(["-v", "error", "-select_streams", "v:0", "-show_entries"])
        .arg("stream=avg_frame_rate,r_frame_rate,width,height,duration:format=duration")
        .args(["-of", "json"])
        .arg(path)
        .stdin(Stdio::null())
        .output()
        .context("cannot run ffprobe (install ffmpeg)")?;
    if !out.status.success() {
        bail!("ffprobe failed on {}: {}", path.display(), String::from_utf8_lossy(&out.stderr).trim());
    }
    let v: serde_json::Value = serde_json::from_slice(&out.stdout)?;
    let stream = v["streams"].get(0).ok_or_else(|| anyhow::anyhow!("{} has no video stream", path.display()))?;
    let rate = |s: &serde_json::Value| -> Option<f64> {
        let s = s.as_str()?;
        let (a, b) = s.split_once('/').unwrap_or((s, "1"));
        let (a, b): (f64, f64) = (a.parse().ok()?, b.parse().ok()?);
        (b > 0.0 && a > 0.0).then(|| a / b)
    };
    let fps = rate(&stream["avg_frame_rate"]).or_else(|| rate(&stream["r_frame_rate"])).unwrap_or(30.0);
    let num = |s: &serde_json::Value| s.as_str().and_then(|s| s.parse::<f64>().ok());
    let duration = num(&v["format"]["duration"])
        .or_else(|| num(&stream["duration"]))
        .ok_or_else(|| anyhow::anyhow!("cannot find the length of {}", path.display()))?;
    if duration <= 0.0 {
        bail!("{} has zero length", path.display());
    }
    Ok(Probe {
        duration,
        fps: fps.min(240.0),
    })
}

pub struct VideoCfg {
    pub path: PathBuf,
    pub w: u32,
    pub h: u32,
    /// source seconds per display second
    pub speed: f64,
    /// display seconds per output frame
    pub frame_secs: f64,
    /// display seconds per loop
    pub loop_len: f64,
    pub looping: bool,
    pub interpolate: bool,
    pub max_fps: u32,
    filter: String,
}

impl VideoCfg {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        path: &Path,
        w: u32,
        h: u32,
        resize: ResizeMode,
        fill: [u8; 3],
        speed: Option<f64>,
        duration: Option<f64>,
        looping: bool,
        interpolate: Interpolate,
        max_fps: u32,
    ) -> Result<VideoCfg> {
        let p = probe(path)?;
        let speed = match (duration, speed) {
            (Some(d), _) if d > 0.0 => p.duration / d,
            (_, Some(s)) if s > 0.0 => s,
            _ => 1.0,
        };
        let max_fps = max_fps.max(1) as f64;
        // frames per display second we actually want out of ffmpeg
        let out_fps = (p.fps * speed).min(max_fps);
        let time_filter = if speed > 1.0 {
            // ffmpeg itself speeds the clip up and drops what we cannot show
            format!("setpts=PTS/{speed:.9},fps={out_fps:.6}")
        } else {
            // decode at the source rate (or less); we space the frames out ourselves
            format!("fps={:.6}", out_fps / speed)
        };
        let color = format!("0x{:02x}{:02x}{:02x}", fill[0], fill[1], fill[2]);
        let scale = match resize {
            ResizeMode::Crop => format!("scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h}"),
            ResizeMode::Fit => format!(
                "scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color={color}"
            ),
            ResizeMode::Stretch => format!("scale={w}:{h}"),
            ResizeMode::Center => {
                format!("crop='min(iw,{w})':'min(ih,{h})',pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color={color}")
            }
        };
        let frame_secs = 1.0 / out_fps;
        let interpolate = match interpolate {
            Interpolate::On => true,
            Interpolate::Off => false,
            Interpolate::Auto => frame_secs > 0.1,
        };
        Ok(VideoCfg {
            path: path.to_path_buf(),
            w,
            h,
            speed,
            frame_secs,
            loop_len: p.duration / speed,
            looping,
            interpolate,
            max_fps: max_fps as u32,
            filter: format!("{time_filter},{scale},format=bgr0"),
        })
    }
}

struct Decoder {
    rx: Receiver<Vec<u8>>,
    child: Child,
    /// loop number
    k: i64,
    /// index (within the loop) of the next frame to arrive
    idx: i64,
    produced: u64,
    from_start: bool,
}

impl Drop for Decoder {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Decoder {
    fn spawn(cfg: &VideoCfg, k: i64, display_pos: f64) -> Result<Decoder> {
        let src_pos = (display_pos * cfg.speed).max(0.0);
        let mut cmd = Command::new("ffmpeg");
        cmd.args(["-hide_banner", "-loglevel", "error", "-nostdin"]);
        if src_pos > 0.0 {
            cmd.args(["-ss", &format!("{src_pos:.3}")]);
        }
        cmd.arg("-i").arg(&cfg.path);
        cmd.args(["-an", "-sn", "-dn", "-vf", &cfg.filter, "-f", "rawvideo", "pipe:1"]);
        let mut child = cmd
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .context("cannot run ffmpeg (install ffmpeg)")?;
        let mut stdout = child.stdout.take().unwrap();
        let size = (cfg.w * cfg.h * 4) as usize;
        let (tx, rx) = sync_channel::<Vec<u8>>(2);
        std::thread::Builder::new()
            .name("surw-ffmpeg".into())
            .spawn(move || loop {
                let mut buf = vec![0u8; size];
                if stdout.read_exact(&mut buf).is_err() {
                    break;
                }
                if tx.send(buf).is_err() {
                    break;
                }
            })?;
        Ok(Decoder { rx, child, k, idx: (display_pos / cfg.frame_secs).round() as i64, produced: 0, from_start: src_pos <= 0.0 })
    }
}

pub struct VideoContent {
    cfg: VideoCfg,
    origin: SystemTime,
    dec: Option<Decoder>,
    pre: Option<Decoder>,
    cur: Option<(SystemTime, Frame)>,
    next: Option<(SystemTime, Frame)>,
    ended: bool,
    blend: Frame,
    blend_level: i64,
    /// the timeline follows the wall clock, so pausing does not move it
    pub clock_locked: bool,
}

impl VideoContent {
    /// `origin` is when loop 0 frame 0 is (or was) shown.
    pub fn new(cfg: VideoCfg, origin: SystemTime, now: SystemTime) -> Result<VideoContent> {
        let blend = Frame::solid(cfg.w, cfg.h, [0, 0, 0]);
        let mut v = VideoContent {
            cfg,
            origin,
            dec: None,
            pre: None,
            cur: None,
            next: None,
            ended: false,
            blend,
            blend_level: -1,
            clock_locked: false,
        };
        v.seek(now)?;
        // Wait for the first picture so the wallpaper never starts black.
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        while v.cur.is_none() && !v.ended && std::time::Instant::now() < deadline {
            if let Some(f) = v.pull(Duration::from_millis(100)) {
                v.cur = Some(f);
            }
        }
        if v.cur.is_none() {
            bail!("ffmpeg produced no frames for {}", v.cfg.path.display());
        }
        Ok(v)
    }

    fn frame_time(&self, k: i64, idx: i64) -> SystemTime {
        let secs = k as f64 * self.cfg.loop_len + idx as f64 * self.cfg.frame_secs;
        if secs >= 0.0 {
            self.origin + Duration::from_secs_f64(secs)
        } else {
            self.origin - Duration::from_secs_f64(-secs)
        }
    }

    fn seek(&mut self, now: SystemTime) -> Result<()> {
        self.dec = None;
        self.pre = None;
        self.next = None;
        self.ended = false;
        let el = signed_secs(now, self.origin);
        let len = self.cfg.loop_len;
        let (k, pos) = if self.cfg.looping {
            let k = (el / len).floor();
            (k as i64, el - k * len)
        } else {
            (0, el.clamp(0.0, (len - 2.0 * self.cfg.frame_secs).max(0.0)))
        };
        // Start one frame early so the frame that should be on screen right now comes first.
        let pos = (pos - self.cfg.frame_secs).max(0.0);
        self.dec = Some(Decoder::spawn(&self.cfg, k, pos)?);
        if self.cfg.looping {
            self.pre = Decoder::spawn(&self.cfg, k + 1, 0.0).ok();
        }
        Ok(())
    }

    /// Takes the next decoded frame, switching to the next loop's decoder at the end of a loop.
    fn pull(&mut self, timeout: Duration) -> Option<(SystemTime, Frame)> {
        for _ in 0..3 {
            let dec = self.dec.as_mut()?;
            match dec.rx.recv_timeout(timeout) {
                Ok(data) => {
                    let (k, idx) = (dec.k, dec.idx);
                    dec.idx += 1;
                    dec.produced += 1;
                    let t = self.frame_time(k, idx);
                    return Some((t, Frame { w: self.cfg.w, h: self.cfg.h, data }));
                }
                Err(RecvTimeoutError::Timeout) => return None,
                Err(RecvTimeoutError::Disconnected) => {
                    let broken = dec.produced == 0 && dec.from_start;
                    let k = dec.k;
                    if !self.cfg.looping || broken {
                        if broken {
                            eprintln!("surw: ffmpeg could not decode {}", self.cfg.path.display());
                        }
                        self.ended = true;
                        self.dec = None;
                        return None;
                    }
                    let next = match self.pre.take() {
                        Some(p) => p,
                        None => match Decoder::spawn(&self.cfg, k + 1, 0.0) {
                            Ok(d) => d,
                            Err(e) => {
                                eprintln!("surw: {e:#}");
                                self.ended = true;
                                return None;
                            }
                        },
                    };
                    self.pre = Decoder::spawn(&self.cfg, next.k + 1, 0.0).ok();
                    self.dec = Some(next);
                }
            }
        }
        None
    }
}

fn signed_secs(a: SystemTime, b: SystemTime) -> f64 {
    match a.duration_since(b) {
        Ok(d) => d.as_secs_f64(),
        Err(e) => -e.duration().as_secs_f64(),
    }
}

impl Content for VideoContent {
    fn update(&mut self, now: SystemTime) -> bool {
        // Far behind (suspend, long pause, clock jump)? Jump instead of decoding everything.
        if let Some((tn, _)) = &self.next {
            let lag = signed_secs(now, *tn);
            if lag > (3.0f64).max(30.0 * self.cfg.frame_secs) {
                if let Err(e) = self.seek(now) {
                    eprintln!("surw: {e:#}");
                }
            }
        } else if let Some((tc, _)) = &self.cur {
            if signed_secs(now, *tc) < -1.0 {
                // clock went backwards
                let _ = self.seek(now);
            }
        }
        let mut changed = false;
        loop {
            if self.next.is_none() && !self.ended {
                self.next = self.pull(Duration::from_millis(20));
            }
            match &self.next {
                Some((tn, _)) if *tn <= now => {
                    self.cur = self.next.take();
                    changed = true;
                }
                _ => break,
            }
        }
        if changed {
            self.blend_level = -1;
        }
        if self.cfg.interpolate {
            if let (Some((tc, a)), Some((tn, b))) = (&self.cur, &self.next) {
                let span = signed_secs(*tn, *tc).max(1e-6);
                let alpha = (signed_secs(now, *tc) / span).clamp(0.0, 1.0);
                let level = (alpha * 64.0).floor() as i64;
                if level != self.blend_level {
                    self.blend_level = level;
                    fade(&a.data, &b.data, &mut self.blend.data, level as f64 / 64.0);
                    changed = true;
                }
            }
        }
        changed
    }

    fn frame(&self) -> &Frame {
        if self.cfg.interpolate && self.blend_level >= 0 && self.next.is_some() {
            return &self.blend;
        }
        &self.cur.as_ref().expect("video has a first frame").1
    }

    fn next_wake(&self, now: SystemTime) -> Option<SystemTime> {
        match (&self.cur, &self.next) {
            (Some((tc, _)), Some((tn, _))) => {
                if self.cfg.interpolate {
                    let span = signed_secs(*tn, *tc).max(0.0);
                    let step = (span / 64.0).max(1.0 / self.cfg.max_fps as f64);
                    let t = now + Duration::from_secs_f64(step);
                    Some(if t < *tn { t } else { *tn })
                } else {
                    Some(*tn)
                }
            }
            _ if self.ended => None,
            // decoder has not caught up yet
            _ => Some(now + Duration::from_millis(5)),
        }
    }

    fn shift(&mut self, by: Duration) {
        if self.clock_locked {
            return;
        }
        self.origin += by;
        if let Some((t, _)) = &mut self.cur {
            *t += by;
        }
        if let Some((t, _)) = &mut self.next {
            *t += by;
        }
    }
}
