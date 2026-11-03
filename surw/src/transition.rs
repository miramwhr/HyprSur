//! Drawing a transition between two frames at a given progress.
//!
//! Every effect is a pure function of the progress `t` (0..1), so a transition can last
//! one second or 24 hours and survive a daemon restart: we only need the start time.

use crate::spec::{TransitionKind, TransitionSpec};
use std::time::{Duration, SystemTime};

/// How many visibly different steps an effect has on a `w`x`h` screen.
/// Used to redraw a 24h transition only when something would actually change.
pub fn visible_steps(kind: TransitionKind, w: u32, h: u32) -> f64 {
    match kind {
        TransitionKind::None => 1.0,
        TransitionKind::Fade => 255.0,
        TransitionKind::Dissolve => 4096.0,
        _ => ((w as f64).hypot(h as f64)).max(1.0),
    }
}

/// Time between redraws of a running transition.
pub fn step_interval(spec: &TransitionSpec, w: u32, h: u32) -> Duration {
    let min = 1.0 / spec.fps.max(1) as f64;
    let per_step = spec.duration / visible_steps(spec.kind, w, h);
    Duration::from_secs_f64(per_step.max(min))
}

pub fn resolve_random(kind: TransitionKind, seed: u64) -> TransitionKind {
    if kind != TransitionKind::Random {
        return kind;
    }
    const ALL: [TransitionKind; 6] = [
        TransitionKind::Fade,
        TransitionKind::Wipe,
        TransitionKind::Slide,
        TransitionKind::Grow,
        TransitionKind::Outer,
        TransitionKind::Dissolve,
    ];
    ALL[(hash(seed) % ALL.len() as u64) as usize]
}

pub fn hash(mut x: u64) -> u64 {
    x ^= x >> 33;
    x = x.wrapping_mul(0xff51afd7ed558ccd);
    x ^= x >> 33;
    x = x.wrapping_mul(0xc4ceb9fe1a85ec53);
    x ^= x >> 33;
    x
}

/// Progress (already eased) of a transition that started at `start`.
pub fn progress(spec: &TransitionSpec, start: SystemTime, now: SystemTime) -> f64 {
    if spec.duration <= 0.0 || spec.kind == TransitionKind::None {
        return 1.0;
    }
    let el = now.duration_since(start).map(|d| d.as_secs_f64()).unwrap_or(0.0);
    spec.easing.apply(el / spec.duration)
}

/// Quantizes a progress value to the visible steps, so redraws are skipped when nothing changes.
pub fn quantize(t: f64, kind: TransitionKind, w: u32, h: u32) -> f64 {
    let steps = visible_steps(kind, w, h);
    (t * steps).floor() / steps
}

/// Draws `from` -> `to` at progress `t` into `out`. All three must have the same size.
pub fn compose(kind: TransitionKind, spec: &TransitionSpec, from: &[u8], to: &[u8], out: &mut [u8], w: u32, h: u32, t: f64) {
    let t = t.clamp(0.0, 1.0);
    if t <= 0.0 {
        out.copy_from_slice(from);
        return;
    }
    if t >= 1.0 {
        out.copy_from_slice(to);
        return;
    }
    match kind {
        TransitionKind::None => out.copy_from_slice(to),
        TransitionKind::Fade | TransitionKind::Random => fade(from, to, out, t),
        TransitionKind::Dissolve => dissolve(from, to, out, w, t),
        TransitionKind::Wipe => wipe(spec.angle, from, to, out, w, h, t),
        TransitionKind::Slide => slide(spec.angle, from, to, out, w, h, t),
        TransitionKind::Grow => circle(spec.pos, false, from, to, out, w, h, t),
        TransitionKind::Outer => circle(spec.pos, true, from, to, out, w, h, t),
    }
}

pub fn fade(from: &[u8], to: &[u8], out: &mut [u8], t: f64) {
    let a = (t * 256.0).round().clamp(0.0, 256.0) as u16;
    let b = 256 - a;
    for ((o, f), n) in out.iter_mut().zip(from).zip(to) {
        *o = ((*f as u16 * b + *n as u16 * a) >> 8) as u8;
    }
}

fn dissolve(from: &[u8], to: &[u8], out: &mut [u8], w: u32, t: f64) {
    // Blocks of 4x4 pixels flip in a fixed pseudo-random order.
    let threshold = (t * u32::MAX as f64) as u64;
    for (i, ((o, f), n)) in out.chunks_exact_mut(4).zip(from.chunks_exact(4)).zip(to.chunks_exact(4)).enumerate() {
        let x = (i as u32 % w) / 4;
        let y = (i as u32 / w) / 4;
        let r = hash(((y as u64) << 32) | x as u64) & 0xffff_ffff;
        o.copy_from_slice(if r < threshold { n } else { f });
    }
}

fn wipe(angle: f64, from: &[u8], to: &[u8], out: &mut [u8], w: u32, h: u32, t: f64) {
    let (dy, dx) = (-angle.to_radians()).sin_cos();
    // Projection range over the four corners.
    let corners = [(0.0, 0.0), (w as f64, 0.0), (0.0, h as f64), (w as f64, h as f64)];
    let proj: Vec<f64> = corners.iter().map(|(x, y)| x * dx + y * dy).collect();
    let pmin = proj.iter().cloned().fold(f64::MAX, f64::min);
    let pmax = proj.iter().cloned().fold(f64::MIN, f64::max);
    let soft = ((w as f64).hypot(h as f64) * 0.03).max(1.0);
    let edge = pmin - soft + t * (pmax - pmin + 2.0 * soft);
    for y in 0..h {
        let row = (y * w * 4) as usize;
        for x in 0..w {
            let i = row + (x * 4) as usize;
            let p = x as f64 * dx + y as f64 * dy;
            // 1 = fully new, 0 = fully old
            let a = ((edge - p) / soft + 0.5).clamp(0.0, 1.0);
            blend_px(&from[i..i + 4], &to[i..i + 4], &mut out[i..i + 4], a);
        }
    }
}

fn slide(angle: f64, from: &[u8], to: &[u8], out: &mut [u8], w: u32, h: u32, t: f64) {
    // Snap the angle to one of four directions; the new picture enters from the opposite side.
    let a = angle.rem_euclid(360.0);
    let dir = ((a + 45.0) / 90.0) as u32 % 4; // 0 = moving right, 1 = up, 2 = left, 3 = down
    let (w_i, h_i) = (w as i64, h as i64);
    let (ox, oy) = match dir {
        0 => ((t * w as f64) as i64, 0),
        1 => (0, -((t * h as f64) as i64)),
        2 => (-((t * w as f64) as i64), 0),
        _ => (0, (t * h as f64) as i64),
    };
    for y in 0..h_i {
        for x in 0..w_i {
            let o = ((y * w_i + x) * 4) as usize;
            // Old picture moved by (ox, oy); new picture moved by (ox, oy) - (±w, ±h).
            let (fx, fy) = (x - ox, y - oy);
            let src: &[u8] = if fx >= 0 && fx < w_i && fy >= 0 && fy < h_i {
                let i = ((fy * w_i + fx) * 4) as usize;
                &from[i..i + 4]
            } else {
                let (nx, ny) = match dir {
                    0 => (fx + w_i, fy),
                    1 => (fx, fy - h_i),
                    2 => (fx - w_i, fy),
                    _ => (fx, fy + h_i),
                };
                let nx = nx.clamp(0, w_i - 1);
                let ny = ny.clamp(0, h_i - 1);
                let i = ((ny * w_i + nx) * 4) as usize;
                &to[i..i + 4]
            };
            out[o..o + 4].copy_from_slice(src);
        }
    }
}

fn circle(pos: (f64, f64), shrink: bool, from: &[u8], to: &[u8], out: &mut [u8], w: u32, h: u32, t: f64) {
    let cx = pos.0 * w as f64;
    let cy = pos.1 * h as f64;
    let corners = [(0.0, 0.0), (w as f64, 0.0), (0.0, h as f64), (w as f64, h as f64)];
    let maxd = corners.iter().map(|(x, y)| (x - cx).hypot(y - cy)).fold(0.0, f64::max);
    let soft = ((w as f64).hypot(h as f64) * 0.01).max(1.0);
    let r = if shrink { (1.0 - t) * (maxd + soft) } else { t * (maxd + soft) };
    for y in 0..h {
        let row = (y * w * 4) as usize;
        let dy = y as f64 - cy;
        for x in 0..w {
            let i = row + (x * 4) as usize;
            let d = (x as f64 - cx).hypot(dy);
            let inside = ((r - d) / soft + 0.5).clamp(0.0, 1.0);
            let a = if shrink { 1.0 - inside } else { inside };
            blend_px(&from[i..i + 4], &to[i..i + 4], &mut out[i..i + 4], a);
        }
    }
}

#[inline]
fn blend_px(f: &[u8], n: &[u8], o: &mut [u8], a: f64) {
    if a <= 0.0 {
        o.copy_from_slice(f);
    } else if a >= 1.0 {
        o.copy_from_slice(n);
    } else {
        let a = (a * 256.0) as u16;
        let b = 256 - a;
        for k in 0..4 {
            o[k] = ((f[k] as u16 * b + n[k] as u16 * a) >> 8) as u8;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoints_are_exact() {
        let spec = TransitionSpec::default();
        let from = vec![10u8; 16 * 9 * 4];
        let to = vec![200u8; 16 * 9 * 4];
        let mut out = vec![0u8; 16 * 9 * 4];
        for kind in [TransitionKind::Fade, TransitionKind::Wipe, TransitionKind::Slide, TransitionKind::Grow, TransitionKind::Outer, TransitionKind::Dissolve] {
            compose(kind, &spec, &from, &to, &mut out, 16, 9, 0.0);
            assert_eq!(out, from, "{kind:?} at 0");
            compose(kind, &spec, &from, &to, &mut out, 16, 9, 1.0);
            assert_eq!(out, to, "{kind:?} at 1");
            compose(kind, &spec, &from, &to, &mut out, 16, 9, 0.5);
        }
    }

    #[test]
    fn long_transitions_redraw_rarely() {
        let spec = TransitionSpec { duration: 86400.0, ..Default::default() };
        let step = step_interval(&spec, 1920, 1080);
        assert!(step.as_secs() > 300, "fade over 24h should redraw every ~5 minutes, got {step:?}");
    }
}
