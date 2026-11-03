//! Where the sun is, for macOS-style "solar" dynamic wallpapers.
//! Low-precision NOAA / Astronomical Almanac formulas, good to about a degree.

use chrono::{DateTime, Local, NaiveDate, TimeZone};

/// Sun altitude and azimuth in degrees (azimuth: 0 = north, 90 = east) at a unix time.
pub fn position(unix: f64, lat: f64, lon: f64) -> (f64, f64) {
    let n = unix / 86400.0 + 2440587.5 - 2451545.0;
    let l = (280.460 + 0.9856474 * n).rem_euclid(360.0);
    let g = (357.528 + 0.9856003 * n).rem_euclid(360.0).to_radians();
    let lambda = (l + 1.915 * g.sin() + 0.020 * (2.0 * g).sin()).to_radians();
    let eps = (23.439 - 0.0000004 * n).to_radians();
    let ra = (eps.cos() * lambda.sin()).atan2(lambda.cos());
    let dec = (eps.sin() * lambda.sin()).asin();
    let gmst_h = (18.697374558 + 24.06570982441908 * n).rem_euclid(24.0);
    let ha = (gmst_h * 15.0 + lon).to_radians() - ra;
    let lat = lat.to_radians();
    let alt = (lat.sin() * dec.sin() + lat.cos() * dec.cos() * ha.cos()).asin();
    let az = (-ha.sin()).atan2(dec.tan() * lat.cos() - lat.sin() * ha.cos());
    (alt.to_degrees(), az.to_degrees().rem_euclid(360.0))
}

/// Unix time of local midnight starting `date`.
pub fn local_midnight(date: NaiveDate) -> f64 {
    let naive = date.and_hms_opt(0, 0, 0).unwrap();
    match Local.from_local_datetime(&naive) {
        chrono::LocalResult::Single(t) | chrono::LocalResult::Ambiguous(t, _) => t.timestamp() as f64,
        chrono::LocalResult::None => Local.from_utc_datetime(&naive).timestamp() as f64,
    }
}

pub fn today() -> NaiveDate {
    let now: DateTime<Local> = Local::now();
    now.date_naive()
}

/// A guess at the location from the time zone, used when no coordinates are configured.
pub fn guess_location() -> (f64, f64) {
    let offset = Local::now().offset().local_minus_utc() as f64 / 3600.0;
    (45.0, offset * 15.0)
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Event {
    Sunrise,
    Sunset,
    Dawn,
    Dusk,
    Noon,
    Midnight,
}

impl Event {
    pub fn parse(s: &str) -> Option<Event> {
        Some(match s {
            "sunrise" | "восход" => Event::Sunrise,
            "sunset" | "закат" => Event::Sunset,
            "dawn" | "рассвет" => Event::Dawn,
            "dusk" | "сумерки" => Event::Dusk,
            "noon" | "solar-noon" | "полдень" => Event::Noon,
            "midnight" | "полночь" => Event::Midnight,
            _ => return None,
        })
    }
}

const STEP: f64 = 60.0;

/// Unix time of a sun event on the given local day. Falls back to sensible clock times
/// during polar day / night.
pub fn event_time(event: Event, date: NaiveDate, lat: f64, lon: f64) -> f64 {
    let start = local_midnight(date);
    let alt = |t: f64| position(t, lat, lon).0;
    let crossing = |target: f64, rising: bool| -> Option<f64> {
        let mut prev = alt(start);
        let mut t = start;
        while t < start + 86400.0 {
            let nt = t + STEP;
            let a = alt(nt);
            let hit = if rising { prev < target && a >= target } else { prev >= target && a < target };
            if hit {
                // linear interpolation inside the minute
                let f = (target - prev) / (a - prev);
                return Some(t + f * STEP);
            }
            prev = a;
            t = nt;
        }
        None
    };
    let extreme = |max: bool| -> f64 {
        let mut best = start;
        let mut best_a = alt(start);
        let mut t = start;
        while t < start + 86400.0 {
            let a = alt(t);
            if (max && a > best_a) || (!max && a < best_a) {
                best_a = a;
                best = t;
            }
            t += STEP;
        }
        best
    };
    let hour = |h: f64| start + h * 3600.0;
    match event {
        Event::Sunrise => crossing(-0.833, true).unwrap_or(hour(6.0)),
        Event::Sunset => crossing(-0.833, false).unwrap_or(hour(18.0)),
        Event::Dawn => crossing(-6.0, true).unwrap_or(hour(5.5)),
        Event::Dusk => crossing(-6.0, false).unwrap_or(hour(18.5)),
        Event::Noon => extreme(true),
        Event::Midnight => start,
    }
}

/// Time on the given local day when the sun is closest to the given altitude/azimuth
/// (this is how macOS "solar" dynamic wallpapers place their pictures).
pub fn time_of_position(alt: f64, az: f64, date: NaiveDate, lat: f64, lon: f64) -> f64 {
    let start = local_midnight(date);
    let mut best = start;
    let mut best_d = f64::MAX;
    let mut t = start;
    while t < start + 86400.0 {
        let (a, z) = position(t, lat, lon);
        let dz = ((z - az + 540.0).rem_euclid(360.0) - 180.0) * a.to_radians().cos().max(0.2);
        let d = (a - alt).powi(2) + dz.powi(2);
        if d < best_d {
            best_d = d;
            best = t;
        }
        t += STEP * 2.0;
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn noon_is_high_in_summer_moscow() {
        // 2024-06-21 09:30 UTC is close to solar noon in Moscow (37.6E)
        let t = 1718962200.0;
        let (alt, az) = position(t, 55.75, 37.62);
        assert!((alt - 57.7).abs() < 1.5, "alt {alt}");
        assert!((az - 180.0).abs() < 10.0, "az {az}");
    }

    #[test]
    fn night_is_below_horizon() {
        // 2024-12-21 21:00 UTC, midnight in Moscow
        let (alt, _) = position(1734814800.0, 55.75, 37.62);
        assert!(alt < -40.0, "alt {alt}");
    }
}
