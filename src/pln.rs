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
            if candidates
                .iter()
                .any(|(na, nc, _, _, _)| *na == a && *nc == c)
            {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::atomspace::AtomSpace;

    fn tv(s: f64, c: f64) -> TruthValue {
        TruthValue::new(s, c)
    }

    fn chain_abc() -> AtomSpace {
        let mut s = AtomSpace::new();
        let (a, _) = s.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.85));
        let (b, _) = s.add_node(AtomType::ConceptNode, "mammal", tv(0.95, 0.90));
        let (c, _) = s.add_node(AtomType::ConceptNode, "animal", tv(0.95, 0.90));
        s.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.90));
        s.add_link(AtomType::InheritanceLink, vec![b, c], tv(0.95, 0.90));
        s
    }

    #[test]
    fn deduction_basic() {
        let mut s = chain_abc();
        let inf = forward_chain(&mut s, 1);
        assert_eq!(inf.len(), 1);
        assert_eq!(inf[0].rule, "deduction");
        let cat = s.find_node(AtomType::ConceptNode, "cat").unwrap();
        let animal = s.find_node(AtomType::ConceptNode, "animal").unwrap();
        assert!(s
            .find_link(AtomType::InheritanceLink, &[cat, animal])
            .is_some());
    }

    #[test]
    fn deduction_tv_formula() {
        let mut s = chain_abc();
        let inf = forward_chain(&mut s, 1);
        let t = inf[0].tv;
        // s = 0.95 * 0.95 = 0.9025
        assert!((t.strength - 0.9025).abs() < 0.001);
        // c = min(0.90, 0.90) * 0.9 = 0.81
        assert!((t.confidence - 0.81).abs() < 0.001);
    }

    #[test]
    fn no_self_loops() {
        let mut s = AtomSpace::new();
        let (a, _) = s.add_node(AtomType::ConceptNode, "x", tv(0.9, 0.9));
        let (b, _) = s.add_node(AtomType::ConceptNode, "y", tv(0.9, 0.9));
        s.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.9));
        s.add_link(AtomType::InheritanceLink, vec![b, a], tv(0.95, 0.9));
        let inf = forward_chain(&mut s, 1);
        assert!(inf.is_empty(), "should not produce a->a or b->b");
    }

    #[test]
    fn transitive_chain_depth_2() {
        let mut s = AtomSpace::new();
        let (a, _) = s.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.85));
        let (b, _) = s.add_node(AtomType::ConceptNode, "mammal", tv(0.95, 0.90));
        let (c, _) = s.add_node(AtomType::ConceptNode, "animal", tv(0.95, 0.90));
        let (d, _) = s.add_node(AtomType::ConceptNode, "thing", tv(0.99, 0.99));
        s.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.90));
        s.add_link(AtomType::InheritanceLink, vec![b, c], tv(0.95, 0.90));
        s.add_link(AtomType::InheritanceLink, vec![c, d], tv(0.99, 0.95));
        let inf = forward_chain(&mut s, 2);
        // Depth 1: cat->animal, mammal->thing
        // Depth 2: cat->thing, + potentially more
        assert!(inf.len() >= 3, "expected >=3 inferences, got {}", inf.len());
        assert!(s.find_link(AtomType::InheritanceLink, &[a, d]).is_some());
    }

    #[test]
    fn no_duplicate_inferences() {
        let mut s = chain_abc();
        forward_chain(&mut s, 1);
        let inf2 = forward_chain(&mut s, 1);
        assert!(
            inf2.is_empty(),
            "re-running should produce 0 new inferences"
        );
    }

    #[test]
    fn confidence_degrades_through_chain() {
        let mut s = AtomSpace::new();
        let (a, _) = s.add_node(AtomType::ConceptNode, "a", tv(0.9, 0.9));
        let (b, _) = s.add_node(AtomType::ConceptNode, "b", tv(0.9, 0.9));
        let (c, _) = s.add_node(AtomType::ConceptNode, "c", tv(0.9, 0.9));
        let (d, _) = s.add_node(AtomType::ConceptNode, "d", tv(0.9, 0.9));
        s.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.90));
        s.add_link(AtomType::InheritanceLink, vec![b, c], tv(0.95, 0.90));
        s.add_link(AtomType::InheritanceLink, vec![c, d], tv(0.95, 0.90));
        let inf = forward_chain(&mut s, 3);
        // Find a->d (longest chain)
        if let Some(ad) = inf.iter().find(|i| {
            let atom = s.get(i.conclusion_id).unwrap();
            atom.outgoing == vec![a, d]
        }) {
            assert!(
                ad.tv.confidence < 0.81,
                "long chains should degrade confidence"
            );
        }
    }

    #[test]
    fn empty_space_no_inferences() {
        let mut s = AtomSpace::new();
        let inf = forward_chain(&mut s, 3);
        assert!(inf.is_empty());
    }
}
