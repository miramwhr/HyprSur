//! macOS-style dynamic wallpapers: a set of pictures tied to the time of day or to the
//! position of the sun, blending slowly from one to the next.
//!
//! ```toml
//! latitude = 55.75          # optional; without it sun times are guessed from the time zone
//! longitude = 37.62
//! transition = "fade"       # any transition
//! transition_duration = "full"   # "full" = blend through the whole gap (macOS), or e.g. "1h"
//!
//! [[frame]]
//! time = "sunrise"          # "07:30", "sunrise", "sunset-30m", "noon+2h", "dawn", "dusk", "midnight"
//! image = "morning.jpg"     # relative to this file
//!
//! [[frame]]
//! sun = { altitude = 40, azimuth = 180 }   # like macOS "solar" wallpapers
//! image = "day.jpg"
//! ```
//!
//! Each picture is fully on screen exactly at its time; the blend into it ends there.

use crate::content::{Moment, Schedule};
use crate::duration::parse_duration;
use crate::spec::{unix_to_system, Easing, TransitionKind, TransitionSpec};
use crate::sun;
use anyhow::{bail, Context, Result};
use chrono::NaiveDate;
use serde::Deserialize;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct File {
    latitude: Option<f64>,
    longitude: Option<f64>,
    transition: Option<TransitionKind>,
    transition_duration: Option<String>,
    easing: Option<Easing>,
    angle: Option<f64>,
    fps: Option<u32>,
    #[serde(rename = "frame", default)]
    frames: Vec<FrameDef>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct FrameDef {
    time: Option<String>,
    sun: Option<SunPos>,
    image: String,
}

#[derive(Debug, Deserialize, Clone, Copy)]
struct SunPos {
    altitude: f64,
    azimuth: f64,
}

#[derive(Debug, Clone)]
enum When {
    Clock(f64),
    Event(sun::Event, f64),
    Sun(SunPos),
}

pub struct Dynamic {
    pub images: Vec<PathBuf>,
    pub transition: TransitionSpec,
    /// None = blend over the whole gap between two pictures
    blend: Option<f64>,
    whens: Vec<When>,
    lat: f64,
    lon: f64,
    /// keyframes (unix time, picture) for yesterday, today and tomorrow
    cache: Option<(NaiveDate, Vec<(f64, usize)>)>,
}

fn parse_when(s: &str) -> Result<When> {
    let s = s.trim().to_lowercase();
    // event with offset: "sunset-30m", "noon + 2h"
    let split = s.find(['+', '-']);
    let (head, off) = match split {
        Some(i) => (s[..i].trim(), Some((&s[i..i + 1], s[i + 1..].trim()))),
        None => (s.as_str(), None),
    };
    if let Some(ev) = sun::Event::parse(head) {
        let off = match off {
            Some((sign, d)) => parse_duration(d)? * if sign == "-" { -1.0 } else { 1.0 },
            None => 0.0,
        };
        return Ok(When::Event(ev, off));
    }
    let parts: Vec<&str> = s.split(':').collect();
    if (2..=3).contains(&parts.len()) {
        let h: f64 = parts[0].parse().context("bad hour")?;
        let m: f64 = parts[1].parse().context("bad minute")?;
        let sec: f64 = if parts.len() == 3 { parts[2].parse().context("bad second")? } else { 0.0 };
        if !(0.0..24.0).contains(&h) || !(0.0..60.0).contains(&m) {
            bail!("time out of range: {s}");
        }
        return Ok(When::Clock(h * 3600.0 + m * 60.0 + sec));
    }
    bail!("cannot understand time `{s}` (use 07:30, sunrise, sunset-30m, noon+1h, dawn, dusk, midnight)")
}

impl Dynamic {
    pub fn load(path: &Path, lat: Option<f64>, lon: Option<f64>) -> Result<Dynamic> {
        let text = std::fs::read_to_string(path).with_context(|| format!("cannot read {}", path.display()))?;
        let file: File = toml::from_str(&text).with_context(|| format!("bad dynamic wallpaper file {}", path.display()))?;
        if file.frames.is_empty() {
            bail!("{} has no [[frame]] entries", path.display());
        }
        let dir = path.parent().unwrap_or(Path::new("."));
        let mut images = Vec::new();
        let mut whens = Vec::new();
        for f in &file.frames {
            let img = crate::expand_home(&f.image);
            let img = if img.is_absolute() { img } else { dir.join(img) };
            if !img.exists() {
                bail!("picture {} does not exist", img.display());
            }
            images.push(img);
            whens.push(match (&f.time, f.sun) {
                (Some(t), None) => parse_when(t)?,
                (None, Some(s)) => When::Sun(s),
                _ => bail!("each [[frame]] needs exactly one of `time` or `sun` (picture {})", f.image),
            });
        }
        let blend = match file.transition_duration.as_deref().map(str::trim) {
            None | Some("full") | Some("all") | Some("smooth") => None,
            Some(d) => Some(parse_duration(d)?),
        };
        let (glat, glon) = sun::guess_location();
        let needs_sun = whens.iter().any(|w| !matches!(w, When::Clock(_)));
        let lat = lat.or(file.latitude);
        let lon = lon.or(file.longitude);
        if needs_sun && (lat.is_none() || lon.is_none()) {
            eprintln!("surw: no latitude/longitude in {}, guessing sun times from the time zone", path.display());
        }
        let transition = TransitionSpec {
            kind: file.transition.unwrap_or(TransitionKind::Fade),
            duration: blend.unwrap_or(0.0),
            easing: file.easing.unwrap_or(Easing::Linear),
            angle: file.angle.unwrap_or(0.0),
            fps: file.fps.unwrap_or(30),
            ..Default::default()
        };
        Ok(Dynamic {
            images,
            transition,
            blend,
            whens,
            lat: lat.unwrap_or(glat),
            lon: lon.unwrap_or(glon),
            cache: None,
        })
    }

    fn day_keyframes(&self, date: NaiveDate) -> Vec<(f64, usize)> {
        let midnight = sun::local_midnight(date);
        let mut v: Vec<(f64, usize)> = self
            .whens
            .iter()
            .enumerate()
            .map(|(i, w)| {
                let t = match w {
                    When::Clock(s) => midnight + s,
                    When::Event(ev, off) => sun::event_time(*ev, date, self.lat, self.lon) + off,
                    When::Sun(p) => sun::time_of_position(p.altitude, p.azimuth, date, self.lat, self.lon),
                };
                (t, i)
            })
            .collect();
        v.sort_by(|a, b| a.0.total_cmp(&b.0));
        v
    }

    fn keyframes(&mut self, now: f64) -> &[(f64, usize)] {
        let date = chrono::DateTime::from_timestamp(now as i64, 0)
            .map(|d| d.with_timezone(&chrono::Local).date_naive())
            .unwrap_or_else(sun::today);
        if self.cache.as_ref().map(|c| c.0) != Some(date) {
            let mut all = Vec::new();
            for d in [date.pred_opt().unwrap(), date, date.succ_opt().unwrap()] {
                all.extend(self.day_keyframes(d));
            }
            all.sort_by(|a, b| a.0.total_cmp(&b.0));
            self.cache = Some((date, all));
        }
        &self.cache.as_ref().unwrap().1
    }

    /// Times of today's pictures, for `surw query` style printing and tests.
    pub fn today_schedule(&self) -> Vec<(f64, PathBuf)> {
        self.day_keyframes(sun::today()).into_iter().map(|(t, i)| (t, self.images[i].clone())).collect()
    }
}

impl Schedule for Dynamic {
    fn at(&mut self, now_st: SystemTime) -> Moment {
        let now = crate::spec::system_to_unix(now_st);
        let blend = self.blend;
        let kf = self.keyframes(now).to_vec();
        // last keyframe at or before now
        let i = kf.iter().rposition(|(t, _)| *t <= now).unwrap_or(0);
        let window = |a: usize| -> (f64, f64) {
            // the blend into keyframe a+1
            let (ta, _) = kf[a];
            let (tb, _) = kf[(a + 1).min(kf.len() - 1)];
            let d = blend.unwrap_or(f64::MAX).min(tb - ta).max(0.0);
            (tb - d, tb)
        };
        let st = unix_to_system;
        if i + 1 >= kf.len() {
            let (t, img) = kf[i];
            return Moment { from: img, to: img, start: st(t), end: st(t), next_start: st(now + 3600.0) };
        }
        let (ws, we) = window(i);
        let next_start = if i + 2 < kf.len() { window(i + 1).0 } else { we + 3600.0 };
        if now >= ws && now < we && kf[i].1 != kf[i + 1].1 {
            Moment { from: kf[i].1, to: kf[i + 1].1, start: st(ws), end: st(we), next_start: st(next_start) }
        } else {
            let img = kf[i].1;
            Moment { from: img, to: img, start: st(kf[i].0), end: st(kf[i].0), next_start: st(if now < ws { ws } else { we }) }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_times() {
        assert!(matches!(parse_when("07:30").unwrap(), When::Clock(s) if s == 27000.0));
        assert!(matches!(parse_when("sunset-30m").unwrap(), When::Event(sun::Event::Sunset, o) if o == -1800.0));
        assert!(matches!(parse_when("noon + 2h").unwrap(), When::Event(sun::Event::Noon, o) if o == 7200.0));
        assert!(parse_when("teatime").is_err());
    }
}
