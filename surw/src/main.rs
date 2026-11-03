#![allow(clippy::too_many_arguments, clippy::large_enum_variant)]

mod content;
mod daemon;
mod duration;
mod dynamic;
mod frame;
mod heic;
mod ipc;
mod scene;
mod slideshow;
mod spec;
mod sun;
mod transition;
mod video;

use anyhow::{bail, Context, Result};
use clap::{Args, Parser, Subcommand};
use ipc::{Request, Response};
use spec::{now_unix, Easing, Interpolate, ResizeMode, Source, TransitionKind, TransitionSpec, Wallpaper};
use std::path::{Path, PathBuf};

/// surw — the HyprSur wallpaper daemon.
///
/// Static pictures, videos that can be stretched to any length (`--duration 24hours`),
/// transitions that can last seconds or a whole day, and macOS-style dynamic wallpapers
/// that follow the clock or the sun.
#[derive(Parser)]
#[command(name = "surw", version, about, long_about = None)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Run the daemon (put `exec-once = surw daemon` in hyprland.conf)
    #[command(alias = "init")]
    Daemon {
        /// Start with a black screen instead of the last wallpaper
        #[arg(long)]
        no_restore: bool,
    },
    /// Show a picture (png, jpg, webp, ...). Videos and GIFs are passed on to `video`.
    Img {
        path: PathBuf,
        #[command(flatten)]
        common: Common,
        #[command(flatten)]
        tr: TransitionArgs,
    },
    /// Play a video (mp4, webm, mkv, gif, ...) as the wallpaper
    Video {
        path: PathBuf,
        /// Stretch or squeeze one loop to this length: `24hours`, `10m`, `30s`
        #[arg(long, conflicts_with = "speed")]
        duration: Option<String>,
        /// Playback speed: 0.5 = twice as slow, 2 = twice as fast
        #[arg(long)]
        speed: Option<f64>,
        /// Tie the position to the time of day: with `--duration 24h` noon in the video is noon outside
        #[arg(long)]
        sync_clock: bool,
        /// Play once and stay on the last frame
        #[arg(long)]
        once: bool,
        /// Blend frames when the video is slowed down a lot
        #[arg(long, value_enum, default_value_t = Interpolate::Auto)]
        interpolate: Interpolate,
        /// Highest frame rate to draw
        #[arg(long, default_value_t = 60)]
        max_fps: u32,
        #[command(flatten)]
        common: Common,
        #[command(flatten)]
        tr: TransitionArgs,
    },
    /// Fill the screen with one color, e.g. `surw color '#1e1e2e'`
    Color {
        color: String,
        #[arg(short, long, value_delimiter = ',')]
        outputs: Vec<String>,
        #[command(flatten)]
        tr: TransitionArgs,
    },
    /// A dynamic wallpaper (TOML schedule) that changes with the time of day or the sun, like on macOS
    Dynamic {
        /// The .toml schedule (see README); `surw import-heic` makes one from a macOS .heic
        path: PathBuf,
        /// Your latitude, for sunrise/sunset times (overrides the file)
        #[arg(long, allow_hyphen_values = true)]
        latitude: Option<f64>,
        /// Your longitude (east is positive)
        #[arg(long, allow_hyphen_values = true)]
        longitude: Option<f64>,
        /// Print today's schedule and exit
        #[arg(long)]
        print: bool,
        #[command(flatten)]
        common: Common,
        #[command(flatten)]
        tr: TransitionArgs,
    },
    /// Change pictures from a folder every so often ("change picture every hour")
    Slideshow {
        /// Folders and/or pictures
        #[arg(required = true)]
        paths: Vec<PathBuf>,
        /// How long each picture stays: `30m`, `1h`, `24hours`
        #[arg(long, default_value = "30m")]
        every: String,
        /// Random order
        #[arg(long)]
        shuffle: bool,
        /// Count from midnight, so with `--every 1h` pictures change on the hour
        #[arg(long)]
        align: bool,
        #[command(flatten)]
        common: Common,
        #[command(flatten)]
        tr: TransitionArgs,
    },
    /// Freeze animations (videos, transitions)
    Pause {
        #[arg(short, long, value_delimiter = ',')]
        outputs: Vec<String>,
    },
    /// Continue after `pause`
    Resume {
        #[arg(short, long, value_delimiter = ',')]
        outputs: Vec<String>,
    },
    /// Show what every monitor is displaying
    Query,
    /// Stop the daemon
    Kill,
    /// Turn a macOS dynamic wallpaper (.heic) into pictures + a schedule for `surw dynamic`
    ImportHeic {
        path: PathBuf,
        /// Folder for the pictures and schedule.toml
        #[arg(short, long)]
        out: Option<PathBuf>,
    },
}

#[derive(Args)]
struct Common {
    /// Monitors to change, comma separated (default: all), e.g. `-o DP-1,HDMI-A-1`
    #[arg(short, long, value_delimiter = ',')]
    outputs: Vec<String>,
    /// How to fit the picture to the screen
    #[arg(long, value_enum, default_value_t = ResizeMode::Crop)]
    resize: ResizeMode,
    /// Color around the picture for --resize fit/center
    #[arg(long, default_value = "000000")]
    fill_color: String,
}

#[derive(Args)]
struct TransitionArgs {
    /// Transition effect from the current wallpaper
    #[arg(short = 't', long = "transition", value_enum, default_value_t = TransitionKind::Fade)]
    kind: TransitionKind,
    /// How long the transition takes: `1s`, `10m`, `24hours`
    #[arg(id = "transition_duration", long = "transition-duration", visible_alias = "td", default_value = "1s")]
    duration: String,
    #[arg(long, value_enum, default_value_t = Easing::EaseInOut)]
    easing: Easing,
    /// Direction for wipe / slide in degrees (0 = left to right, 90 = bottom to top)
    #[arg(long, default_value_t = 0.0, allow_hyphen_values = true)]
    angle: f64,
    /// Center for grow / outer: `center`, `top-left`, or `x,y` in 0..1
    #[arg(long, default_value = "center")]
    pos: String,
    /// Highest redraw rate during the transition
    #[arg(long = "transition-fps", default_value_t = 60)]
    fps: u32,
}

impl TransitionArgs {
    fn spec(&self) -> Result<TransitionSpec> {
        let duration = if self.kind == TransitionKind::None { 0.0 } else { duration::parse_duration(&self.duration)? };
        Ok(TransitionSpec {
            kind: self.kind,
            duration,
            easing: self.easing,
            angle: self.angle,
            pos: spec::parse_pos(&self.pos)?,
            fps: self.fps,
        })
    }
}

pub fn expand_home(p: impl AsRef<Path>) -> PathBuf {
    let p = p.as_ref();
    if let Ok(rest) = p.strip_prefix("~") {
        if let Ok(home) = std::env::var("HOME") {
            return PathBuf::from(home).join(rest);
        }
    }
    p.to_path_buf()
}

fn absolute(p: &Path) -> Result<PathBuf> {
    let p = expand_home(p);
    std::fs::canonicalize(&p).with_context(|| format!("{} does not exist", p.display()))
}

fn wallpaper(source: Source, common: &Common) -> Result<Wallpaper> {
    Ok(Wallpaper { source, resize: common.resize, fill: spec::parse_color(&common.fill_color)?, started_at: now_unix() })
}

fn set(outputs: Vec<String>, wallpaper: Wallpaper, transition: TransitionSpec) -> Result<()> {
    request(Request::Set { outputs, wallpaper, transition })
}

fn request(req: Request) -> Result<()> {
    match ipc::send(&req)? {
        Response::Ok { message } => {
            if let Some(m) = message {
                eprintln!("surw: {m}");
            }
            Ok(())
        }
        Response::Error { message } => bail!(message),
        Response::Info { outputs } => {
            for o in outputs {
                println!(
                    "{}: {}x{} scale {}{}\n  {}",
                    o.name,
                    o.width,
                    o.height,
                    o.scale,
                    if o.paused { " (paused)" } else { "" },
                    o.wallpaper
                );
                if let Some(t) = o.transition {
                    println!("  transition: {t}");
                }
            }
            Ok(())
        }
    }
}

fn video_source(path: PathBuf, duration: Option<String>, speed: Option<f64>, sync_clock: bool, once: bool, interpolate: Interpolate, max_fps: u32) -> Result<Source> {
    let probe = video::probe(&path)?;
    let duration = duration.map(|d| duration::parse_duration(&d)).transpose()?;
    if let Some(d) = duration {
        if d <= 0.0 {
            bail!("--duration must be longer than zero");
        }
        eprintln!(
            "surw: {} lasts {}, one loop will take {} (x{:.4} speed)",
            path.display(),
            duration::format_duration(probe.duration),
            duration::format_duration(d),
            probe.duration / d
        );
    }
    if let Some(s) = speed {
        if s <= 0.0 {
            bail!("--speed must be above zero");
        }
    }
    Ok(Source::Video { path, speed, duration, sync_clock, looping: !once, interpolate, max_fps })
}

fn collect_pictures(paths: &[PathBuf]) -> Result<Vec<PathBuf>> {
    let mut out = Vec::new();
    for p in paths {
        let p = absolute(p)?;
        if p.is_dir() {
            let mut v: Vec<PathBuf> = std::fs::read_dir(&p)?
                .flatten()
                .map(|e| e.path())
                .filter(|e| frame::has_ext(e, frame::IMAGE_EXTS))
                .collect();
            v.sort();
            out.extend(v);
        } else {
            out.push(p);
        }
    }
    if out.is_empty() {
        bail!("no pictures found");
    }
    Ok(out)
}

fn main() {
    if let Err(e) = real_main() {
        eprintln!("surw: {e:#}");
        std::process::exit(1);
    }
}

fn real_main() -> Result<()> {
    let cli = Cli::parse();
    match cli.cmd {
        Cmd::Daemon { no_restore } => daemon::run(no_restore),
        Cmd::Img { path, common, tr } => {
            let path = absolute(&path)?;
            let source = if frame::has_ext(&path, frame::VIDEO_EXTS) {
                video_source(path, None, None, false, false, Interpolate::Auto, 60)?
            } else {
                image::image_dimensions(&path).with_context(|| format!("cannot read picture {}", path.display()))?;
                Source::Image { path }
            };
            set(common.outputs.clone(), wallpaper(source, &common)?, tr.spec()?)
        }
        Cmd::Video { path, duration, speed, sync_clock, once, interpolate, max_fps, common, tr } => {
            let path = absolute(&path)?;
            let source = video_source(path, duration, speed, sync_clock, once, interpolate, max_fps)?;
            set(common.outputs.clone(), wallpaper(source, &common)?, tr.spec()?)
        }
        Cmd::Color { color, outputs, tr } => {
            let rgb = spec::parse_color(&color)?;
            let wp = Wallpaper { source: Source::Color { rgb }, resize: ResizeMode::Crop, fill: rgb, started_at: now_unix() };
            set(outputs, wp, tr.spec()?)
        }
        Cmd::Dynamic { path, latitude, longitude, print, common, tr } => {
            let path = absolute(&path)?;
            let d = dynamic::Dynamic::load(&path, latitude, longitude)?;
            if print {
                for (t, img) in d.today_schedule() {
                    let local = chrono::DateTime::from_timestamp(t as i64, 0).unwrap().with_timezone(&chrono::Local);
                    println!("{}  {}", local.format("%H:%M"), img.display());
                }
                return Ok(());
            }
            let source = Source::Dynamic { path, latitude, longitude };
            set(common.outputs.clone(), wallpaper(source, &common)?, tr.spec()?)
        }
        Cmd::Slideshow { paths, every, shuffle, align, common, tr } => {
            let paths = collect_pictures(&paths)?;
            let every = duration::parse_duration(&every)?;
            let transition = tr.spec()?;
            let source = Source::Slideshow { paths, every, shuffle, align, transition: transition.clone() };
            set(common.outputs.clone(), wallpaper(source, &common)?, transition)
        }
        Cmd::Pause { outputs } => request(Request::Pause { outputs }),
        Cmd::Resume { outputs } => request(Request::Resume { outputs }),
        Cmd::Query => request(Request::Query),
        Cmd::Kill => request(Request::Kill),
        Cmd::ImportHeic { path, out } => {
            let path = absolute(&path)?;
            let out = out.unwrap_or_else(|| path.with_extension(""));
            let toml = heic::import(&path, &out)?;
            println!("{}", toml.display());
            eprintln!("surw: now run `surw dynamic {}`", toml.display());
            Ok(())
        }
    }
}
