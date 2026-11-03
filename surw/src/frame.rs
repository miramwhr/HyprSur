//! A screen-sized picture in the compositor's native XRGB8888 layout (bytes B, G, R, X).

use crate::spec::ResizeMode;
use anyhow::{Context, Result};
use image::{imageops::FilterType, RgbaImage};
use std::path::Path;

#[derive(Clone)]
pub struct Frame {
    pub w: u32,
    pub h: u32,
    pub data: Vec<u8>,
}

impl Frame {
    pub fn solid(w: u32, h: u32, rgb: [u8; 3]) -> Frame {
        let mut data = vec![0u8; (w * h * 4) as usize];
        for px in data.chunks_exact_mut(4) {
            px[0] = rgb[2];
            px[1] = rgb[1];
            px[2] = rgb[0];
            px[3] = 255;
        }
        Frame { w, h, data }
    }

    pub fn load(path: &Path, w: u32, h: u32, mode: ResizeMode, fill: [u8; 3]) -> Result<Frame> {
        let img = image::open(path)
            .with_context(|| format!("cannot open image {}", path.display()))?
            .to_rgba8();
        Ok(Frame::from_rgba(&img, w, h, mode, fill))
    }

    pub fn from_rgba(img: &RgbaImage, w: u32, h: u32, mode: ResizeMode, fill: [u8; 3]) -> Frame {
        let (iw, ih) = img.dimensions();
        let mut out = Frame::solid(w, h, fill);
        if iw == 0 || ih == 0 || w == 0 || h == 0 {
            return out;
        }
        // Work out the scaled size and the offset of the scaled picture on the screen.
        let (sw, sh) = match mode {
            ResizeMode::Stretch => (w, h),
            ResizeMode::Center => (iw, ih),
            ResizeMode::Crop | ResizeMode::Fit => {
                let sx = w as f64 / iw as f64;
                let sy = h as f64 / ih as f64;
                let s = if mode == ResizeMode::Crop { sx.max(sy) } else { sx.min(sy) };
                (((iw as f64 * s).round() as u32).max(1), ((ih as f64 * s).round() as u32).max(1))
            }
        };
        let scaled;
        let src = if (sw, sh) == (iw, ih) {
            img
        } else {
            let filter = if sw < iw / 2 { FilterType::Triangle } else { FilterType::CatmullRom };
            scaled = image::imageops::resize(img, sw, sh, filter);
            &scaled
        };
        let off_x = (w as i64 - sw as i64) / 2;
        let off_y = (h as i64 - sh as i64) / 2;
        for y in 0..h as i64 {
            let sy = y - off_y;
            if sy < 0 || sy >= sh as i64 {
                continue;
            }
            for x in 0..w as i64 {
                let sx = x - off_x;
                if sx < 0 || sx >= sw as i64 {
                    continue;
                }
                let p = src.get_pixel(sx as u32, sy as u32).0;
                let i = ((y as u32 * w + x as u32) * 4) as usize;
                let a = p[3] as u32;
                let d = &mut out.data[i..i + 4];
                if a == 255 {
                    d[0] = p[2];
                    d[1] = p[1];
                    d[2] = p[0];
                } else {
                    d[0] = ((p[2] as u32 * a + d[0] as u32 * (255 - a)) / 255) as u8;
                    d[1] = ((p[1] as u32 * a + d[1] as u32 * (255 - a)) / 255) as u8;
                    d[2] = ((p[0] as u32 * a + d[2] as u32 * (255 - a)) / 255) as u8;
                }
            }
        }
        out
    }
}

pub const IMAGE_EXTS: &[&str] = &["png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "jfif"];
pub const VIDEO_EXTS: &[&str] = &[
    "mp4", "mkv", "webm", "mov", "avi", "m4v", "gif", "apng", "ogv", "mpg", "mpeg", "wmv", "flv",
];

pub fn has_ext(path: &Path, exts: &[&str]) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| exts.contains(&e.to_lowercase().as_str()))
        .unwrap_or(false)
}
