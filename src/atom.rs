use std::fmt;

/// Simple truth value: strength (probability) and confidence (weight of evidence)
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TruthValue {
    pub strength: f64,
    pub confidence: f64,
}

impl TruthValue {
    pub fn new(strength: f64, confidence: f64) -> Self {
        Self {
            strength: strength.clamp(0.0, 1.0),
            confidence: confidence.clamp(0.0, 1.0),
        }
    }

    pub fn default_tv() -> Self {
        Self::new(1.0, 0.0)
    }

    pub fn is_valid(&self) -> bool {
        (0.0..=1.0).contains(&self.strength) && (0.0..=1.0).contains(&self.confidence)
    }
}

impl fmt::Display for TruthValue {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "stv {:.2}/{:.2}", self.strength, self.confidence)
    }
}

/// Attention value: short-term importance (STI) and long-term importance (LTI)
#[derive(Debug, Clone, Copy)]
pub struct AttentionValue {
    pub sti: f64,
    pub lti: f64,
}

impl AttentionValue {
    pub fn zero() -> Self {
        Self { sti: 0.0, lti: 0.0 }
    }
}

/// Types of atoms in the hypergraph
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AtomType {
    ConceptNode,
    PredicateNode,
    InheritanceLink,
    EvaluationLink,
    ListLink,
}

impl AtomType {
    pub fn is_node(self) -> bool {
        matches!(self, AtomType::ConceptNode | AtomType::PredicateNode)
    }

    pub fn is_link(self) -> bool {
        !self.is_node()
    }
}

impl fmt::Display for AtomType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AtomType::ConceptNode => write!(f, "ConceptNode"),
            AtomType::PredicateNode => write!(f, "PredicateNode"),
            AtomType::InheritanceLink => write!(f, "InheritanceLink"),
            AtomType::EvaluationLink => write!(f, "EvaluationLink"),
            AtomType::ListLink => write!(f, "ListLink"),
        }
    }
}

pub type AtomId = u64;

/// A single atom in the AtomSpace hypergraph
#[derive(Debug, Clone)]
pub struct Atom {
    pub id: AtomId,
    pub atom_type: AtomType,
    pub name: Option<String>,      // For nodes
    pub outgoing: Vec<AtomId>,     // For links
    pub tv: TruthValue,
    pub av: AttentionValue,
}

impl Atom {
    pub fn new_node(id: AtomId, atom_type: AtomType, name: &str, tv: TruthValue) -> Self {
        Self {
            id,
            atom_type,
            name: Some(name.to_string()),
            outgoing: Vec::new(),
            tv,
            av: AttentionValue::zero(),
        }
    }

    pub fn new_link(id: AtomId, atom_type: AtomType, outgoing: Vec<AtomId>, tv: TruthValue) -> Self {
        Self {
            id,
            atom_type,
            name: None,
            outgoing,
            tv,
            av: AttentionValue::zero(),
        }
    }
}
