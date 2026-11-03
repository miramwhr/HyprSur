//! Human friendly durations: `24hours`, `24h`, `1h30m`, `90s`, `500ms`, `2.5s`, `1d`,
//! and the same in Russian: `24часа`, `30мин`, `10сек`, `1день`.

use anyhow::{bail, Result};

/// Parses a duration into seconds. A bare number means seconds.
pub fn parse_duration(input: &str) -> Result<f64> {
    let s: String = input.trim().to_lowercase().chars().filter(|c| !c.is_whitespace()).collect();
    if s.is_empty() {
        bail!("пустая длительность / empty duration");
    }
    // "HH:MM:SS" or "MM:SS"
    if s.contains(':') {
        let mut total = 0.0;
        for part in s.split(':') {
            let v: f64 = part.parse().map_err(|_| anyhow::anyhow!("bad duration `{input}`"))?;
            total = total * 60.0 + v;
        }
        return Ok(total);
    }

    let mut total = 0.0;
    let mut rest = s.as_str();
    let mut any = false;
    while !rest.is_empty() {
        let num_len = rest
            .char_indices()
            .find(|(_, c)| !(c.is_ascii_digit() || *c == '.' || *c == ','))
            .map(|(i, _)| i)
            .unwrap_or(rest.len());
        if num_len == 0 {
            bail!("bad duration `{input}`: expected a number before `{rest}`");
        }
        let num: f64 = rest[..num_len]
            .replace(',', ".")
            .parse()
            .map_err(|_| anyhow::anyhow!("bad duration `{input}`"))?;
        rest = &rest[num_len..];
        let unit_len = rest
            .char_indices()
            .find(|(_, c)| c.is_ascii_digit() || *c == '.' || *c == ',')
            .map(|(i, _)| i)
            .unwrap_or(rest.len());
        let unit = &rest[..unit_len];
        rest = &rest[unit_len..];
        let mult = match unit_multiplier(unit) {
            Some(m) => m,
            None => bail!("unknown time unit `{unit}` in `{input}` (use ms, s, m, h, d)"),
        };
        total += num * mult;
        any = true;
    }
    if !any {
        bail!("bad duration `{input}`");
    }
    Ok(total)
}

fn unit_multiplier(unit: &str) -> Option<f64> {
    Some(match unit {
        "" | "s" | "sec" | "secs" | "second" | "seconds" | "с" | "сек" | "секунд" | "секунда"
        | "секунды" => 1.0,
        "ms" | "msec" | "millis" | "milliseconds" | "мс" => 0.001,
        "m" | "min" | "mins" | "minute" | "minutes" | "м" | "мин" | "минут" | "минута" | "минуты" => 60.0,
        "h" | "hr" | "hrs" | "hour" | "hours" | "ч" | "час" | "часа" | "часов" => 3600.0,
        "d" | "day" | "days" | "д" | "день" | "дня" | "дней" | "сутки" | "суток" => 86400.0,
        "w" | "week" | "weeks" | "нед" | "неделя" | "недели" | "недель" => 7.0 * 86400.0,
        _ => return None,
    })
}

/// Formats seconds back into a short human string (`1h30m`, `2.5s`).
pub fn format_duration(secs: f64) -> String {
    if secs < 1.0 {
        return format!("{}ms", (secs * 1000.0).round());
    }
    if secs < 60.0 {
        let r = (secs * 10.0).round() / 10.0;
        return format!("{r}s");
    }
    let mut s = secs.round() as u64;
    let mut out = String::new();
    for (unit, size) in [("d", 86400u64), ("h", 3600), ("m", 60), ("s", 1)] {
        if s >= size {
            out.push_str(&format!("{}{unit}", s / size));
            s %= size;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_many_forms() {
        assert_eq!(parse_duration("24hours").unwrap(), 86400.0);
        assert_eq!(parse_duration("24 hours").unwrap(), 86400.0);
        assert_eq!(parse_duration("24h").unwrap(), 86400.0);
        assert_eq!(parse_duration("1h30m").unwrap(), 5400.0);
        assert_eq!(parse_duration("90").unwrap(), 90.0);
        assert_eq!(parse_duration("500ms").unwrap(), 0.5);
        assert_eq!(parse_duration("2.5s").unwrap(), 2.5);
        assert_eq!(parse_duration("1d").unwrap(), 86400.0);
        assert_eq!(parse_duration("24часа").unwrap(), 86400.0);
        assert_eq!(parse_duration("30 мин").unwrap(), 1800.0);
        assert_eq!(parse_duration("01:00:00").unwrap(), 3600.0);
        assert!(parse_duration("abc").is_err());
        assert!(parse_duration("5 parsecs").is_err());
    }

    #[test]
    fn formats() {
        assert_eq!(format_duration(5400.0), "1h30m");
        assert_eq!(format_duration(2.5), "2.5s");
        assert_eq!(format_duration(86400.0), "1d");
    }
}
