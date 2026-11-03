//! Import of macOS dynamic wallpapers (`.heic`, Mojave and later).
//!
//! Apple stores the schedule as a base64 binary plist inside the XMP metadata:
//! * `apple_desktop:h24`   — time of day: `ti = [{ i: picture, t: fraction of the day }]`
//! * `apple_desktop:solar` — sun position: `si = [{ i: picture, a: altitude, z: azimuth }]`
//! * `apple_desktop:apr`   — only light / dark: `{ l: picture, d: picture }`
//!
//! The pictures are extracted with `heif-convert` (package libheif-examples / libheif).

use anyhow::{bail, Context, Result};
use base64::Engine;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, PartialEq)]
pub enum AppleSchedule {
    Time(Vec<(usize, f64)>),
    Solar(Vec<(usize, f64, f64)>),
    Appearance { light: usize, dark: usize },
}

/// Finds the schedule in the raw bytes of a .heic file.
pub fn find_schedule(bytes: &[u8]) -> Result<AppleSchedule> {
    for (attr, kind) in [("apple_desktop:solar", 0), ("apple_desktop:h24", 1), ("apple_desktop:apr", 2)] {
        if let Some(b64) = find_attr(bytes, attr) {
            let data = base64::engine::general_purpose::STANDARD
                .decode(b64.trim())
                .with_context(|| format!("bad base64 in {attr}"))?;
            let v: plist::Value = plist::from_bytes(&data).with_context(|| format!("bad plist in {attr}"))?;
            return parse_plist(kind, &v);
        }
    }
    bail!("this .heic has no macOS dynamic wallpaper metadata (apple_desktop:h24 / solar / apr)")
}

fn find_attr(bytes: &[u8], attr: &str) -> Option<String> {
    let text = String::from_utf8_lossy(bytes);
    // attribute form:  apple_desktop:h24="..."
    if let Some(i) = text.find(&format!("{attr}=\"")) {
        let rest = &text[i + attr.len() + 2..];
        return rest.find('"').map(|e| rest[..e].to_string());
    }
    // element form:  <apple_desktop:h24>...</apple_desktop:h24>
    if let Some(i) = text.find(&format!("<{attr}>")) {
        let rest = &text[i + attr.len() + 2..];
        return rest.find('<').map(|e| rest[..e].to_string());
    }
    None
}

fn num(v: Option<&plist::Value>) -> Option<f64> {
    match v? {
        plist::Value::Real(r) => Some(*r),
        plist::Value::Integer(i) => i.as_signed().map(|x| x as f64),
        _ => None,
    }
}

fn parse_plist(kind: u8, v: &plist::Value) -> Result<AppleSchedule> {
    let dict = v.as_dictionary().context("plist is not a dictionary")?;
    match kind {
        0 => {
            let si = dict.get("si").and_then(|s| s.as_array()).context("solar plist has no `si`")?;
            let mut out = Vec::new();
            for e in si {
                let d = e.as_dictionary().context("bad `si` entry")?;
                out.push((
                    num(d.get("i")).context("no picture index")? as usize,
                    num(d.get("a")).context("no altitude")?,
                    num(d.get("z")).context("no azimuth")?,
                ));
            }
            Ok(AppleSchedule::Solar(out))
        }
        1 => {
            let ti = dict.get("ti").and_then(|s| s.as_array()).context("h24 plist has no `ti`")?;
            let mut out = Vec::new();
            for e in ti {
                let d = e.as_dictionary().context("bad `ti` entry")?;
                out.push((num(d.get("i")).context("no picture index")? as usize, num(d.get("t")).context("no time")?));
            }
            Ok(AppleSchedule::Time(out))
        }
        _ => Ok(AppleSchedule::Appearance {
            light: num(dict.get("l")).unwrap_or(0.0) as usize,
            dark: num(dict.get("d")).unwrap_or(1.0) as usize,
        }),
    }
}

/// Writes the TOML schedule for `surw dynamic`.
pub fn schedule_toml(s: &AppleSchedule, pictures: &[PathBuf]) -> Result<String> {
    let name = |i: usize| -> Result<String> {
        let p = pictures.get(i).with_context(|| format!("picture {i} is missing ({} extracted)", pictures.len()))?;
        Ok(p.file_name().unwrap().to_string_lossy().into_owned())
    };
    let mut out = String::from(
        "# Imported from a macOS dynamic wallpaper by `surw import-heic`.\n\
         # Set your location for accurate sun times:\n\
         # latitude = 55.75\n# longitude = 37.62\n\
         transition = \"fade\"\ntransition_duration = \"full\"\n",
    );
    match s {
        AppleSchedule::Time(v) => {
            let mut v = v.clone();
            v.sort_by(|a, b| a.1.total_cmp(&b.1));
            for (i, t) in v {
                let secs = (t.rem_euclid(1.0) * 86400.0).round() as u64;
                let _ = write!(
                    out,
                    "\n[[frame]]\ntime = \"{:02}:{:02}:{:02}\"\nimage = \"{}\"\n",
                    secs / 3600,
                    secs / 60 % 60,
                    secs % 60,
                    name(i)?
                );
            }
        }
        AppleSchedule::Solar(v) => {
            for (i, a, z) in v {
                let _ = write!(out, "\n[[frame]]\nsun = {{ altitude = {a:.2}, azimuth = {z:.2} }}\nimage = \"{}\"\n", name(*i)?);
            }
        }
        AppleSchedule::Appearance { light, dark } => {
            out = out.replace("transition_duration = \"full\"", "transition_duration = \"1h\"");
            let _ = write!(out, "\n[[frame]]\ntime = \"sunrise+30m\"\nimage = \"{}\"\n", name(*light)?);
            let _ = write!(out, "\n[[frame]]\ntime = \"sunset+30m\"\nimage = \"{}\"\n", name(*dark)?);
        }
    }
    Ok(out)
}

pub fn import(path: &Path, out_dir: &Path) -> Result<PathBuf> {
    let bytes = std::fs::read(path).with_context(|| format!("cannot read {}", path.display()))?;
    let schedule = find_schedule(&bytes)?;
    std::fs::create_dir_all(out_dir)?;
    let base = out_dir.join("frame.jpg");
    let status = Command::new("heif-convert")
        .args(["-q", "92"])
        .arg(path)
        .arg(&base)
        .status()
        .context("cannot run heif-convert — install libheif (Arch: `pacman -S libheif`, Debian/Ubuntu: `apt install libheif-examples`)")?;
    if !status.success() {
        bail!("heif-convert failed on {}", path.display());
    }
    // heif-convert writes frame-1.jpg, frame-2.jpg, ... for several pictures, or frame.jpg for one
    let mut pictures: Vec<(usize, PathBuf)> = std::fs::read_dir(out_dir)?
        .flatten()
        .filter_map(|e| {
            let n = e.file_name().to_string_lossy().into_owned();
            let idx = n.strip_prefix("frame-")?.strip_suffix(".jpg")?.parse::<usize>().ok()?;
            Some((idx, e.path()))
        })
        .collect();
    pictures.sort();
    let mut pictures: Vec<PathBuf> = pictures.into_iter().map(|(_, p)| p).collect();
    if pictures.is_empty() && base.exists() {
        pictures.push(base);
    }
    let toml = schedule_toml(&schedule, &pictures)?;
    let toml_path = out_dir.join("schedule.toml");
    std::fs::write(&toml_path, toml)?;
    Ok(toml_path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fake_heic(attr: &str, value: plist::Value) -> Vec<u8> {
        let mut bin = Vec::new();
        plist::to_writer_binary(&mut bin, &value).unwrap();
        let b64 = base64::engine::general_purpose::STANDARD.encode(bin);
        format!("....ftypheic....<x:xmpmeta><rdf:Description {attr}=\"{b64}\"/></x:xmpmeta>....").into_bytes()
    }

    fn dict(pairs: Vec<(&str, plist::Value)>) -> plist::Value {
        plist::Value::Dictionary(pairs.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
    }

    #[test]
    fn reads_h24() {
        let ti = plist::Value::Array(vec![
            dict(vec![("i", 0.into()), ("t", 0.25.into())]),
            dict(vec![("i", 1.into()), ("t", 0.75.into())]),
        ]);
        let bytes = fake_heic("apple_desktop:h24", dict(vec![("ti", ti)]));
        let s = find_schedule(&bytes).unwrap();
        assert_eq!(s, AppleSchedule::Time(vec![(0, 0.25), (1, 0.75)]));
        let toml = schedule_toml(&s, &["a/frame-1.jpg".into(), "a/frame-2.jpg".into()]).unwrap();
        assert!(toml.contains("time = \"06:00:00\"\nimage = \"frame-1.jpg\""), "{toml}");
        assert!(toml.contains("time = \"18:00:00\"\nimage = \"frame-2.jpg\""), "{toml}");
    }

    #[test]
    fn reads_solar() {
        let si = plist::Value::Array(vec![dict(vec![("i", 0.into()), ("a", (-30.5).into()), ("z", 270.0.into())])]);
        let bytes = fake_heic("apple_desktop:solar", dict(vec![("si", si)]));
        assert_eq!(find_schedule(&bytes).unwrap(), AppleSchedule::Solar(vec![(0, -30.5, 270.0)]));
    }
}
