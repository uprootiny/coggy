use std::collections::HashMap;
use crate::atom::*;

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
        self.link_index.get(&(atom_type, outgoing.to_vec())).copied()
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
