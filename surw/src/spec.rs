//! Plain data that travels from the client to the daemon and into the state file.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, clap::ValueEnum, Default)]
#[serde(rename_all = "kebab-case")]
pub enum ResizeMode {
    /// Fill the whole screen, cutting off what does not fit (default)
    #[default]
    Crop,
    /// Show the whole picture, pad the rest with --fill-color
    Fit,
    /// Stretch to the screen size, ignoring aspect ratio
    Stretch,
    /// Do not scale, just center
    Center,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, clap::ValueEnum, Default)]
#[serde(rename_all = "kebab-case")]
pub enum TransitionKind {
    /// Switch instantly
    None,
    /// Cross-fade (the macOS default)
    #[default]
    Fade,
    /// A soft edge sweeps across the screen at --angle
    Wipe,
    /// The new picture slides in, pushing the old one out (direction from --angle)
    Slide,
    /// A circle grows from --pos
    Grow,
    /// A circle shrinks towards --pos
    Outer,
    /// Pixels switch one by one in random order
    Dissolve,
    /// A random one of the above
    Random,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, clap::ValueEnum, Default)]
#[serde(rename_all = "kebab-case")]
pub enum Easing {
    Linear,
    EaseIn,
    EaseOut,
    #[default]
    EaseInOut,
}

impl Easing {
    pub fn apply(self, t: f64) -> f64 {
        let t = t.clamp(0.0, 1.0);
        match self {
            Easing::Linear => t,
            Easing::EaseIn => t * t * t,
            Easing::EaseOut => 1.0 - (1.0 - t).powi(3),
            Easing::EaseInOut => {
                if t < 0.5 {
                    4.0 * t * t * t
                } else {
                    1.0 - (-2.0 * t + 2.0).powi(3) / 2.0
                }
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TransitionSpec {
    pub kind: TransitionKind,
    /// seconds
    pub duration: f64,
    pub easing: Easing,
    /// degrees, 0 = left to right, 90 = bottom to top
    pub angle: f64,
    /// relative position 0..1 for grow/outer, (0,0) = top left
    pub pos: (f64, f64),
    /// maximum redraw rate while animating
    pub fps: u32,
}

impl Default for TransitionSpec {
    fn default() -> Self {
        TransitionSpec {
            kind: TransitionKind::Fade,
            duration: 1.0,
            easing: Easing::EaseInOut,
            angle: 0.0,
            pos: (0.5, 0.5),
            fps: 60,
        }
    }
}

impl TransitionSpec {
    pub fn instant() -> Self {
        TransitionSpec { kind: TransitionKind::None, duration: 0.0, ..Default::default() }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, clap::ValueEnum, Default)]
#[serde(rename_all = "kebab-case")]
pub enum Interpolate {
    /// Blend neighbouring frames when the video is slowed down a lot (one frame lasts longer than 0.1s)
    #[default]
    Auto,
    On,
    Off,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum Source {
    Color {
        rgb: [u8; 3],
    },
    Image {
        path: PathBuf,
    },
    Video {
        path: PathBuf,
        /// playback speed multiplier (already resolved from --duration by the daemon if needed)
        speed: Option<f64>,
        /// wanted length of one loop in seconds (`24hours` => 86400)
        duration: Option<f64>,
        /// tie the playback position to the time of day (like macOS dynamic wallpapers)
        sync_clock: bool,
        looping: bool,
        interpolate: Interpolate,
        max_fps: u32,
    },
    Dynamic {
        path: PathBuf,
        latitude: Option<f64>,
        longitude: Option<f64>,
    },
    Slideshow {
        paths: Vec<PathBuf>,
        /// seconds per picture
        every: f64,
        shuffle: bool,
        /// start the cycle at local midnight so changes land on round clock times
        align: bool,
        transition: TransitionSpec,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Wallpaper {
    pub source: Source,
    pub resize: ResizeMode,
    pub fill: [u8; 3],
    /// unix time in seconds when this wallpaper was set; animations are timed from it
    pub started_at: f64,
}

impl Wallpaper {
    pub fn black() -> Self {
        Wallpaper {
            source: Source::Color { rgb: [0, 0, 0] },
            resize: ResizeMode::Crop,
            fill: [0, 0, 0],
            started_at: now_unix(),
        }
    }

    pub fn describe(&self) -> String {
        match &self.source {
            Source::Color { rgb } => format!("color #{:02x}{:02x}{:02x}", rgb[0], rgb[1], rgb[2]),
            Source::Image { path } => format!("image {}", path.display()),
            Source::Video { path, speed, duration, sync_clock, .. } => {
                let mut s = format!("video {}", path.display());
                if let Some(d) = duration {
                    s.push_str(&format!(", loop {}", crate::duration::format_duration(*d)));
                } else if let Some(sp) = speed {
                    s.push_str(&format!(", speed x{sp}"));
                }
                if *sync_clock {
                    s.push_str(", synced to clock");
                }
                s
            }
            Source::Dynamic { path, .. } => format!("dynamic {}", path.display()),
            Source::Slideshow { paths, every, .. } => format!(
                "slideshow of {} pictures, every {}",
                paths.len(),
                crate::duration::format_duration(*every)
            ),
        }
    }
}

pub fn now_unix() -> f64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs_f64()
}

pub fn unix_to_system(t: f64) -> SystemTime {
    if t >= 0.0 {
        UNIX_EPOCH + Duration::from_secs_f64(t)
    } else {
        UNIX_EPOCH
    }
}

pub fn system_to_unix(t: SystemTime) -> f64 {
    t.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs_f64()
}

pub fn parse_color(s: &str) -> anyhow::Result<[u8; 3]> {
    let h = s.trim().trim_start_matches('#').trim_start_matches("0x");
    let h = if h.len() == 3 {
        h.chars().flat_map(|c| [c, c]).collect::<String>()
    } else {
        h.to_string()
    };
    if h.len() != 6 {
        anyhow::bail!("color must look like #rrggbb, got `{s}`");
    }
    let v = u32::from_str_radix(&h, 16).map_err(|_| anyhow::anyhow!("bad color `{s}`"))?;
    Ok([(v >> 16) as u8, (v >> 8) as u8, v as u8])
}

/// Parses `0.5,0.5`, `center`, `top-left`, ... into relative coordinates.
pub fn parse_pos(s: &str) -> anyhow::Result<(f64, f64)> {
    let named = match s.trim().to_lowercase().as_str() {
        "center" | "centre" => Some((0.5, 0.5)),
        "top" => Some((0.5, 0.0)),
        "bottom" => Some((0.5, 1.0)),
        "left" => Some((0.0, 0.5)),
        "right" => Some((1.0, 0.5)),
        "top-left" => Some((0.0, 0.0)),
        "top-right" => Some((1.0, 0.0)),
        "bottom-left" => Some((0.0, 1.0)),
        "bottom-right" => Some((1.0, 1.0)),
        _ => None,
    };
    if let Some(p) = named {
        return Ok(p);
    }
    let (a, b) = s.split_once(',').ok_or_else(|| anyhow::anyhow!("position must be `x,y` (0..1) or a name like center"))?;
    Ok((a.trim().parse()?, b.trim().parse()?))
}
