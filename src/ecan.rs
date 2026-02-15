//! ECAN — Economic Attention Network
//! Spreads short-term importance (STI) through the hypergraph.

use std::collections::{HashMap, HashSet};
use crate::atom::AtomId;
use crate::atomspace::AtomSpace;

pub struct EcanConfig {
    pub spread_fraction: f64,
    pub decay_factor: f64,
    pub initial_sti: f64,
    pub rent: f64,
}

impl Default for EcanConfig {
    fn default() -> Self {
        Self {
            spread_fraction: 0.3,
            decay_factor: 0.7,
            initial_sti: 40.0,
            rent: 0.5,
        }
    }
}

#[derive(Debug)]
pub struct StiChange {
    pub id: AtomId,
    pub old: f64,
    pub new_val: f64,
}

impl StiChange {
    pub fn boosted(&self) -> bool {
        self.new_val > self.old + 0.1
    }
    pub fn decayed(&self) -> bool {
        self.new_val < self.old - 0.1
    }
}

/// Spread attention from activated atoms through the graph
pub fn spread_attention(
    space: &mut AtomSpace,
    activated: &[AtomId],
    config: &EcanConfig,
) -> Vec<StiChange> {
    let all_ids = space.all_ids();
    let activated_set: HashSet<AtomId> = activated.iter().copied().collect();

    // Snapshot current STI values
    let old_sti: HashMap<AtomId, f64> = all_ids
        .iter()
        .filter_map(|&id| space.get(id).map(|a| (id, a.av.sti)))
        .collect();

    // Phase 1: Boost activated atoms
    for &id in activated {
        if let Some(atom) = space.get_mut(id) {
            let boost = match atom.atom_type {
                crate::atom::AtomType::ListLink => config.initial_sti,
                crate::atom::AtomType::EvaluationLink
                | crate::atom::AtomType::InheritanceLink => config.initial_sti * 0.85,
                crate::atom::AtomType::ConceptNode => config.initial_sti * 0.95,
                crate::atom::AtomType::PredicateNode => config.initial_sti * 0.5,
            };
            atom.av.sti += boost;
        }
    }

    // Phase 2: Collect spreading amounts (read-only pass)
    let mut spreads: Vec<(AtomId, f64)> = Vec::new();
    for &id in &all_ids {
        let (sti, outgoing, is_link) = match space.get(id) {
            Some(a) => (a.av.sti, a.outgoing.clone(), a.atom_type.is_link()),
            None => continue,
        };
        if sti < 1.0 {
            continue;
        }

        // Links spread STI to their outgoing atoms
        if is_link && !outgoing.is_empty() {
            let amount = sti * config.spread_fraction / outgoing.len() as f64;
            for &tgt in &outgoing {
                spreads.push((tgt, amount));
            }
        }

        // All atoms spread weakly to incoming links
        let incoming = space.get_incoming(id);
        if !incoming.is_empty() {
            let amount = sti * config.spread_fraction * 0.3 / incoming.len() as f64;
            for &link_id in &incoming {
                spreads.push((link_id, amount));
            }
        }
    }

    // Apply spreads
    for (id, amount) in spreads {
        if let Some(atom) = space.get_mut(id) {
            atom.av.sti += amount;
        }
    }

    // Phase 3: Decay non-activated atoms
    for &id in &all_ids {
        if activated_set.contains(&id) {
            continue;
        }
        if let Some(atom) = space.get_mut(id) {
            if atom.av.sti > 0.0 {
                atom.av.sti = (atom.av.sti * config.decay_factor - config.rent).max(0.0);
            }
        }
    }

    // Generate change records
    let mut changes = Vec::new();
    for &id in &all_ids {
        let old = old_sti.get(&id).copied().unwrap_or(0.0);
        let cur = space.get(id).map(|a| a.av.sti).unwrap_or(0.0);
        if (cur - old).abs() > 0.01 {
            changes.push(StiChange {
                id,
                old,
                new_val: cur,
            });
        }
    }
    // New atoms (not in old snapshot)
    for &id in activated {
        if !old_sti.contains_key(&id) {
            let cur = space.get(id).map(|a| a.av.sti).unwrap_or(0.0);
            if cur > 0.01 {
                changes.push(StiChange {
                    id,
                    old: 0.0,
                    new_val: cur,
                });
            }
        }
    }

    changes
}
