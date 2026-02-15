//! PLN — Probabilistic Logic Networks
//! Forward-chaining inference on InheritanceLinks.

use crate::atom::*;
use crate::atomspace::AtomSpace;

#[derive(Debug)]
pub struct Inference {
    pub rule: String,
    pub premises: Vec<AtomId>,
    pub conclusion_id: AtomId,
    pub tv: TruthValue,
}

/// PLN deduction truth value formula (simplified):
///   strength:   s_ac = s_ab * s_bc
///   confidence: c_ac = min(c_ab, c_bc) * 0.9
fn deduction_tv(tv_ab: TruthValue, tv_bc: TruthValue) -> TruthValue {
    TruthValue::new(
        tv_ab.strength * tv_bc.strength,
        tv_ab.confidence.min(tv_bc.confidence) * 0.9,
    )
}

/// Run PLN forward chaining up to `max_depth` iterations
pub fn forward_chain(space: &mut AtomSpace, max_depth: u32) -> Vec<Inference> {
    let mut all = Vec::new();
    for _ in 0..max_depth {
        let step = deduction_step(space);
        if step.is_empty() {
            break;
        }
        all.extend(step);
    }
    all
}

/// One step of deduction: A->B, B->C |- A->C
fn deduction_step(space: &mut AtomSpace) -> Vec<Inference> {
    let inh_ids = space.get_by_type(AtomType::InheritanceLink);

    // Collect all inheritance triples: (src, tgt, link_id, tv)
    let links: Vec<(AtomId, AtomId, AtomId, TruthValue)> = inh_ids
        .iter()
        .filter_map(|&id| {
            let atom = space.get(id)?;
            if atom.outgoing.len() == 2 {
                Some((atom.outgoing[0], atom.outgoing[1], id, atom.tv))
            } else {
                None
            }
        })
        .collect();

    // Find deduction opportunities
    let mut candidates: Vec<(AtomId, AtomId, TruthValue, AtomId, AtomId)> = Vec::new();
    for &(a, b, ab_id, tv_ab) in &links {
        for &(b2, c, bc_id, tv_bc) in &links {
            if b != b2 || a == c {
                continue;
            }
            // Skip if conclusion already exists
            if space
                .find_link(AtomType::InheritanceLink, &[a, c])
                .is_some()
            {
                continue;
            }
            // Skip duplicates in this batch
            if candidates.iter().any(|(na, nc, _, _, _)| *na == a && *nc == c) {
                continue;
            }
            let tv = deduction_tv(tv_ab, tv_bc);
            candidates.push((a, c, tv, ab_id, bc_id));
        }
    }

    // Materialize new links
    let mut inferences = Vec::new();
    for (a, c, tv, ab_id, bc_id) in candidates {
        let (id, is_new) = space.add_link(AtomType::InheritanceLink, vec![a, c], tv);
        if is_new {
            inferences.push(Inference {
                rule: "deduction".to_string(),
                premises: vec![ab_id, bc_id],
                conclusion_id: id,
                tv,
            });
        }
    }

    inferences
}
