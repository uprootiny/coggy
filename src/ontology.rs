//! Base ontology loader — biological taxonomy for grounding

use crate::atom::*;
use crate::atomspace::AtomSpace;

pub fn load_base_ontology(space: &mut AtomSpace) -> usize {
    let initial = space.size();

    // Concept nodes
    let concepts: &[(&str, f64, f64)] = &[
        ("thing", 0.99, 0.99),
        ("living-thing", 0.95, 0.95),
        ("non-living", 0.95, 0.95),
        ("animal", 0.95, 0.90),
        ("plant", 0.95, 0.90),
        ("mammal", 0.95, 0.90),
        ("fish", 0.95, 0.90),
        ("bird", 0.95, 0.90),
        ("vegetable", 0.95, 0.90),
        ("cat", 0.90, 0.85),
        ("dog", 0.90, 0.85),
        ("eagle", 0.90, 0.85),
        ("salmon", 0.90, 0.85),
        ("tree", 0.90, 0.85),
        ("flower", 0.90, 0.85),
        ("cucumber", 0.90, 0.85),
        ("can-fly", 0.90, 0.90),
        ("can-swim", 0.90, 0.90),
        ("warm-blooded", 0.90, 0.90),
        ("cold-blooded", 0.90, 0.90),
    ];

    for &(name, s, c) in concepts {
        space.add_node(AtomType::ConceptNode, name, TruthValue::new(s, c));
    }

    // Predicate nodes
    let predicates: &[(&str, f64, f64)] = &[
        ("afraid-of", 0.80, 0.70),
        ("resembles", 0.70, 0.50),
    ];

    for &(name, s, c) in predicates {
        space.add_node(AtomType::PredicateNode, name, TruthValue::new(s, c));
    }

    // Direct inheritance links only — PLN will derive the transitive closure
    let links: &[(&str, &str, f64, f64)] = &[
        ("living-thing", "thing", 0.99, 0.95),
        ("non-living", "thing", 0.99, 0.95),
        ("animal", "living-thing", 0.99, 0.95),
        ("plant", "living-thing", 0.99, 0.95),
        ("mammal", "animal", 0.95, 0.90),
        ("fish", "animal", 0.95, 0.90),
        ("bird", "animal", 0.95, 0.90),
        ("vegetable", "plant", 0.95, 0.90),
        ("cat", "mammal", 0.95, 0.90),
        ("dog", "mammal", 0.95, 0.90),
        ("eagle", "bird", 0.95, 0.90),
        ("salmon", "fish", 0.95, 0.90),
        ("tree", "plant", 0.95, 0.90),
        ("flower", "plant", 0.95, 0.90),
        ("cucumber", "vegetable", 0.90, 0.85),
    ];

    for &(src, tgt, s, c) in links {
        let src_id = space
            .find_node(AtomType::ConceptNode, src)
            .unwrap_or_else(|| panic!("ontology: missing concept '{}'", src));
        let tgt_id = space
            .find_node(AtomType::ConceptNode, tgt)
            .unwrap_or_else(|| panic!("ontology: missing concept '{}'", tgt));
        space.add_link(
            AtomType::InheritanceLink,
            vec![src_id, tgt_id],
            TruthValue::new(s, c),
        );
    }

    space.size() - initial
}
