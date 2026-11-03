//! Client <-> daemon protocol: one JSON line each way over a unix socket.

use crate::spec::{TransitionSpec, Wallpaper};
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::time::Duration;

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "cmd", rename_all = "kebab-case")]
pub enum Request {
    Set { outputs: Vec<String>, wallpaper: Wallpaper, transition: TransitionSpec },
    Pause { outputs: Vec<String> },
    Resume { outputs: Vec<String> },
    Query,
    Kill,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "kebab-case")]
pub enum Response {
    Ok { message: Option<String> },
    Info { outputs: Vec<OutputInfo> },
    Error { message: String },
}

#[derive(Debug, Serialize, Deserialize)]
pub struct OutputInfo {
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub scale: i32,
    pub wallpaper: String,
    pub transition: Option<String>,
    pub paused: bool,
}

pub fn socket_path() -> PathBuf {
    if let Ok(p) = std::env::var("SURW_SOCKET") {
        return p.into();
    }
    let dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".into());
    let display = std::env::var("WAYLAND_DISPLAY").unwrap_or_else(|_| "wayland-0".into());
    let display = display.rsplit('/').next().unwrap_or("wayland-0").to_string();
    PathBuf::from(dir).join(format!("surw-{display}.sock"))
}

pub fn send(req: &Request) -> Result<Response> {
    let path = socket_path();
    let mut stream = UnixStream::connect(&path).with_context(|| {
        format!("cannot reach the daemon at {} — start it with `surw daemon`", path.display())
    })?;
    stream.set_read_timeout(Some(Duration::from_secs(30)))?;
    let mut line = serde_json::to_string(req)?;
    line.push('\n');
    stream.write_all(line.as_bytes())?;
    let mut reader = BufReader::new(stream);
    let mut resp = String::new();
    reader.read_line(&mut resp)?;
    serde_json::from_str(&resp).context("bad answer from the daemon")
}
