use crate::atom::*;
use std::collections::HashMap;

/// The AtomSpace hypergraph — stores atoms with indexed lookups
pub struct AtomSpace {
    atoms: HashMap<AtomId, Atom>,
    next_id: AtomId,
    // Index: (type, name) → id for nodes
    node_index: HashMap<(AtomType, String), AtomId>,
    // Index: (type, outgoing) → id for links
    link_index: HashMap<(AtomType, Vec<AtomId>), AtomId>,
    // Index: type → list of ids
    type_index: HashMap<AtomType, Vec<AtomId>>,
    // Incoming set: atom_id → links that reference it
    incoming: HashMap<AtomId, Vec<AtomId>>,
    pub turn: u32,
}

impl AtomSpace {
    pub fn new() -> Self {
        Self {
            atoms: HashMap::new(),
            next_id: 1,
            node_index: HashMap::new(),
            link_index: HashMap::new(),
            type_index: HashMap::new(),
            incoming: HashMap::new(),
            turn: 0,
        }
    }

    pub fn size(&self) -> usize {
        self.atoms.len()
    }

    pub fn get(&self, id: AtomId) -> Option<&Atom> {
        self.atoms.get(&id)
    }

    pub fn get_mut(&mut self, id: AtomId) -> Option<&mut Atom> {
        self.atoms.get_mut(&id)
    }

    /// Add or retrieve a node. Returns (id, is_new).
    pub fn add_node(&mut self, atom_type: AtomType, name: &str, tv: TruthValue) -> (AtomId, bool) {
        let key = (atom_type, name.to_string());
        if let Some(&id) = self.node_index.get(&key) {
            // Merge: keep higher confidence
            if let Some(atom) = self.atoms.get_mut(&id) {
                if tv.confidence > atom.tv.confidence {
                    atom.tv = tv;
                }
            }
            return (id, false);
        }

        let id = self.next_id;
        self.next_id += 1;
        let atom = Atom::new_node(id, atom_type, name, tv);
        self.atoms.insert(id, atom);
        self.node_index.insert(key, id);
        self.type_index.entry(atom_type).or_default().push(id);
        (id, true)
    }

    /// Add or retrieve a link. Returns (id, is_new).
    pub fn add_link(
        &mut self,
        atom_type: AtomType,
        outgoing: Vec<AtomId>,
        tv: TruthValue,
    ) -> (AtomId, bool) {
        let key = (atom_type, outgoing.clone());
        if let Some(&id) = self.link_index.get(&key) {
            if let Some(atom) = self.atoms.get_mut(&id) {
                if tv.confidence > atom.tv.confidence {
                    atom.tv = tv;
                }
            }
            return (id, false);
        }

        let id = self.next_id;
        self.next_id += 1;
        let atom = Atom::new_link(id, atom_type, outgoing.clone(), tv);
        self.atoms.insert(id, atom);
        self.link_index.insert(key, id);
        self.type_index.entry(atom_type).or_default().push(id);
        for &target in &outgoing {
            self.incoming.entry(target).or_default().push(id);
        }
        (id, true)
    }

    pub fn find_node(&self, atom_type: AtomType, name: &str) -> Option<AtomId> {
        self.node_index.get(&(atom_type, name.to_string())).copied()
    }

    pub fn find_link(&self, atom_type: AtomType, outgoing: &[AtomId]) -> Option<AtomId> {
        self.link_index
            .get(&(atom_type, outgoing.to_vec()))
            .copied()
    }

    pub fn get_by_type(&self, atom_type: AtomType) -> Vec<AtomId> {
        self.type_index.get(&atom_type).cloned().unwrap_or_default()
    }

    pub fn get_incoming(&self, id: AtomId) -> Vec<AtomId> {
        self.incoming.get(&id).cloned().unwrap_or_default()
    }

    pub fn all_ids(&self) -> Vec<AtomId> {
        let mut ids: Vec<_> = self.atoms.keys().copied().collect();
        ids.sort();
        ids
    }

    pub fn all_atoms_sorted(&self) -> Vec<&Atom> {
        let mut atoms: Vec<_> = self.atoms.values().collect();
        atoms.sort_by_key(|a| a.id);
        atoms
    }

    /// Top atoms by STI, descending
    pub fn atoms_by_sti(&self, limit: usize) -> Vec<&Atom> {
        let mut atoms: Vec<_> = self.atoms.values().filter(|a| a.av.sti > 0.01).collect();
        atoms.sort_by(|a, b| {
            b.av.sti
                .partial_cmp(&a.av.sti)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        atoms.truncate(limit);
        atoms
    }

    /// Count of each atom type present
    pub fn types_present(&self) -> Vec<(AtomType, usize)> {
        let mut types: Vec<_> = self
            .type_index
            .iter()
            .map(|(&t, ids)| (t, ids.len()))
            .filter(|(_, c)| *c > 0)
            .collect();
        types.sort_by(|a, b| b.1.cmp(&a.1));
        types
    }

    /// Human-readable name for an atom
    pub fn format_atom(&self, id: AtomId) -> String {
        let Some(atom) = self.atoms.get(&id) else {
            return format!("<unknown:{}>", id);
        };
        if let Some(ref name) = atom.name {
            format!("{}:\"{}\"", atom.atom_type, name)
        } else {
            let parts: Vec<String> = atom
                .outgoing
                .iter()
                .map(|&oid| self.short_name(oid))
                .collect();
            format!("{}:[{}]", atom.atom_type, parts.join("\u{2192}"))
        }
    }

    /// Short name (just the node name or hex id)
    pub fn short_name(&self, id: AtomId) -> String {
        self.atoms
            .get(&id)
            .and_then(|a| a.name.clone())
            .unwrap_or_else(|| format!("{:04x}", id))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tv(s: f64, c: f64) -> TruthValue {
        TruthValue::new(s, c)
    }

    #[test]
    fn add_node_creates_atom() {
        let mut space = AtomSpace::new();
        let (id, is_new) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        assert!(is_new);
        assert_eq!(space.size(), 1);
        let atom = space.get(id).unwrap();
        assert_eq!(atom.name.as_deref(), Some("cat"));
        assert_eq!(atom.atom_type, AtomType::ConceptNode);
    }

    #[test]
    fn node_deduplication() {
        let mut space = AtomSpace::new();
        let (id1, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        let (id2, is_new) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.5));
        assert!(!is_new);
        assert_eq!(id1, id2);
        assert_eq!(space.size(), 1);
    }

    #[test]
    fn node_tv_merge_keeps_higher_confidence() {
        let mut space = AtomSpace::new();
        let (id, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.5));
        space.add_node(AtomType::ConceptNode, "cat", tv(0.8, 0.9));
        assert!((space.get(id).unwrap().tv.confidence - 0.9).abs() < 0.01);
        assert!((space.get(id).unwrap().tv.strength - 0.8).abs() < 0.01);
    }

    #[test]
    fn different_types_not_deduplicated() {
        let mut space = AtomSpace::new();
        let (id1, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        let (id2, is_new) = space.add_node(AtomType::PredicateNode, "cat", tv(0.7, 0.4));
        assert!(is_new);
        assert_ne!(id1, id2);
        assert_eq!(space.size(), 2);
    }

    #[test]
    fn add_link_and_incoming() {
        let mut space = AtomSpace::new();
        let (a, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        let (b, _) = space.add_node(AtomType::ConceptNode, "mammal", tv(0.9, 0.8));
        let (lid, is_new) = space.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.9));
        assert!(is_new);
        assert_eq!(space.size(), 3);
        assert_eq!(space.get(lid).unwrap().outgoing, vec![a, b]);
        assert!(space.get_incoming(a).contains(&lid));
        assert!(space.get_incoming(b).contains(&lid));
    }

    #[test]
    fn link_deduplication() {
        let mut space = AtomSpace::new();
        let (a, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        let (b, _) = space.add_node(AtomType::ConceptNode, "mammal", tv(0.9, 0.8));
        let (id1, _) = space.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.9));
        let (id2, is_new) = space.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.9));
        assert!(!is_new);
        assert_eq!(id1, id2);
        assert_eq!(space.size(), 3);
    }

    #[test]
    fn find_node_and_link() {
        let mut space = AtomSpace::new();
        let (a, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        let (b, _) = space.add_node(AtomType::ConceptNode, "mammal", tv(0.9, 0.8));
        space.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.9));

        assert_eq!(space.find_node(AtomType::ConceptNode, "cat"), Some(a));
        assert!(space.find_node(AtomType::ConceptNode, "dog").is_none());
        assert!(space.find_node(AtomType::PredicateNode, "cat").is_none());
        assert!(space
            .find_link(AtomType::InheritanceLink, &[a, b])
            .is_some());
        assert!(space
            .find_link(AtomType::InheritanceLink, &[b, a])
            .is_none());
    }

    #[test]
    fn get_by_type() {
        let mut space = AtomSpace::new();
        space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        space.add_node(AtomType::ConceptNode, "dog", tv(0.9, 0.8));
        space.add_node(AtomType::PredicateNode, "likes", tv(0.7, 0.4));
        assert_eq!(space.get_by_type(AtomType::ConceptNode).len(), 2);
        assert_eq!(space.get_by_type(AtomType::PredicateNode).len(), 1);
        assert_eq!(space.get_by_type(AtomType::InheritanceLink).len(), 0);
    }

    #[test]
    fn types_present_counts() {
        let mut space = AtomSpace::new();
        space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        space.add_node(AtomType::PredicateNode, "likes", tv(0.7, 0.4));
        let types = space.types_present();
        assert_eq!(types.len(), 2);
    }

    #[test]
    fn format_atom_display() {
        let mut space = AtomSpace::new();
        let (a, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        let (b, _) = space.add_node(AtomType::ConceptNode, "mammal", tv(0.9, 0.8));
        let (lid, _) = space.add_link(AtomType::InheritanceLink, vec![a, b], tv(0.95, 0.9));
        assert_eq!(space.format_atom(a), "ConceptNode:\"cat\"");
        assert!(space.format_atom(lid).contains("cat"));
        assert!(space.format_atom(lid).contains("mammal"));
    }

    #[test]
    fn atoms_by_sti_ordering() {
        let mut space = AtomSpace::new();
        let (a, _) = space.add_node(AtomType::ConceptNode, "cat", tv(0.9, 0.8));
        let (b, _) = space.add_node(AtomType::ConceptNode, "dog", tv(0.9, 0.8));
        space.get_mut(a).unwrap().av.sti = 10.0;
        space.get_mut(b).unwrap().av.sti = 20.0;
        let top = space.atoms_by_sti(5);
        assert_eq!(top.len(), 2);
        assert_eq!(top[0].id, b); // dog has higher STI
        assert_eq!(top[1].id, a);
    }
}
