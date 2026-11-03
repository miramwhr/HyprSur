//! "Change picture every hour", like the macOS wallpaper settings.
//! Which picture is on screen is computed from the clock, so it survives restarts.

use crate::content::{Moment, Schedule};
use crate::spec::unix_to_system;
use crate::transition::hash;
use std::time::{Duration, SystemTime};

pub struct Slideshow {
    n: usize,
    every: f64,
    shuffle: bool,
    trans: f64,
    origin: f64,
    seed: u64,
}

impl Slideshow {
    pub fn new(n: usize, every: f64, shuffle: bool, align: bool, trans: f64, started_at: f64) -> Self {
        let origin = if align {
            let date = chrono::DateTime::from_timestamp(started_at as i64, 0)
                .map(|d| d.with_timezone(&chrono::Local).date_naive())
                .unwrap_or_else(crate::sun::today);
            crate::sun::local_midnight(date)
        } else {
            started_at
        };
        Slideshow {
            n: n.max(1),
            every: every.max(0.1),
            shuffle,
            trans: trans.clamp(0.0, every.max(0.1)),
            origin,
            seed: started_at as u64,
        }
    }

    /// Picture shown in slot `k`. Shuffled order is a fresh permutation every round.
    fn picture(&self, k: i64) -> usize {
        let n = self.n as i64;
        let round = k.div_euclid(n);
        let pos = k.rem_euclid(n) as usize;
        if !self.shuffle || self.n < 2 {
            return pos;
        }
        let mut order: Vec<usize> = (0..self.n).collect();
        let mut s = hash(self.seed ^ (round as u64).wrapping_mul(0x9e3779b97f4a7c15));
        for i in (1..self.n).rev() {
            s = hash(s.wrapping_add(i as u64));
            order.swap(i, (s % (i as u64 + 1)) as usize);
        }
        order[pos]
    }
}

impl Schedule for Slideshow {
    fn at(&mut self, now: SystemTime) -> Moment {
        let el = crate::spec::system_to_unix(now) - self.origin;
        let k = (el / self.every).floor() as i64;
        let slot = self.origin + k as f64 * self.every;
        let to = self.picture(k);
        let from = self.picture(k - 1);
        let next_start = unix_to_system(slot + self.every);
        if el >= 0.0 && el - (k as f64 * self.every) < self.trans && from != to {
            Moment { from, to, start: unix_to_system(slot), end: unix_to_system(slot + self.trans), next_start }
        } else {
            Moment { from: to, to, start: unix_to_system(slot), end: unix_to_system(slot), next_start }
        }
    }

    fn shift(&mut self, by: Duration) {
        self.origin += by.as_secs_f64();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shuffle_visits_everything() {
        let s = Slideshow::new(5, 60.0, true, false, 1.0, 1000.0);
        let mut seen: Vec<usize> = (0..5).map(|k| s.picture(k)).collect();
        seen.sort();
        assert_eq!(seen, vec![0, 1, 2, 3, 4]);
    }
}
