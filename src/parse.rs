//! NL -> Atomese parser
//! Simple rule-based decomposition of natural language into atoms.

use crate::atom::*;
use crate::atomspace::AtomSpace;

#[derive(Debug)]
pub struct ParsedAtom {
    pub id: AtomId,
    pub desc: String,
    pub is_new: bool,
}

pub struct ParseResult {
    pub atoms: Vec<ParsedAtom>,
}

impl ParseResult {
    /// IDs of atoms created for the first time
    pub fn new_ids(&self) -> Vec<AtomId> {
        self.atoms.iter().filter(|a| a.is_new).map(|a| a.id).collect()
    }

    /// All atom IDs referenced (new + existing)
    pub fn all_ids(&self) -> Vec<AtomId> {
        self.atoms.iter().map(|a| a.id).collect()
    }

    pub fn new_count(&self) -> usize {
        self.atoms.iter().filter(|a| a.is_new).count()
    }
}

pub fn parse_input(space: &mut AtomSpace, input: &str) -> ParseResult {
    let input = input.trim().to_lowercase();
    let input = input.trim_end_matches(|c: char| c == '?' || c == '!' || c == '.');
    let words: Vec<&str> = input.split_whitespace().collect();

    if words.is_empty() {
        return ParseResult { atoms: Vec::new() };
    }

    // Pattern: "X is-a Y" / "X isa Y"
    if let Some(pos) = words.iter().position(|&w| w == "is-a" || w == "isa") {
        if pos > 0 && pos < words.len() - 1 {
            let subj = words[..pos].join("-");
            let obj = words[pos + 1..].join("-");
            return make_inheritance(space, &subj, &obj);
        }
    }

    // Pattern: "X is a/an Y"
    if let Some(pos) = words.iter().position(|&w| w == "is") {
        if pos > 0 && pos + 2 <= words.len() - 1 && (words[pos + 1] == "a" || words[pos + 1] == "an") {
            let subj = words[..pos].join("-");
            let obj = words[pos + 2..].join("-");
            return make_inheritance(space, &subj, &obj);
        }
    }

    // Question: "what is X"
    if words.len() >= 3 && words[0] == "what" && words[1] == "is" {
        let obj = words[2..].join("-");
        return make_evaluation(space, "is", "what", &obj);
    }

    // Question: "what can you X"
    if words.len() >= 4 && words[0] == "what" && words[1] == "can" && words[2] == "you" {
        let obj = words[3..].join("-");
        return make_evaluation(space, "can-you", "what", &obj);
    }

    // Question: "who/where/why/how ..."
    let question_words = ["who", "where", "when", "why", "how"];
    if words.len() >= 3 && question_words.contains(&words[0]) {
        let pred = words[1];
        let obj = words[2..].join("-");
        return make_evaluation(space, pred, words[0], &obj);
    }

    // Assertion: "X verb Y" (3+ words)
    if words.len() >= 3 {
        let subj = words[0];
        let pred = words[1];
        let obj = words[2..].join("-");
        return make_evaluation(space, pred, subj, &obj);
    }

    // Two words: concepts + list
    if words.len() == 2 {
        let mut atoms = Vec::new();
        let (id0, n0) = space.add_node(AtomType::ConceptNode, words[0], TruthValue::new(0.80, 0.50));
        atoms.push(ParsedAtom {
            id: id0,
            desc: format!("ConceptNode \"{}\"", words[0]),
            is_new: n0,
        });
        let (id1, n1) = space.add_node(AtomType::ConceptNode, words[1], TruthValue::new(0.80, 0.50));
        atoms.push(ParsedAtom {
            id: id1,
            desc: format!("ConceptNode \"{}\"", words[1]),
            is_new: n1,
        });
        let (lid, ln) = space.add_link(AtomType::ListLink, vec![id0, id1], TruthValue::new(0.0, 0.0));
        atoms.push(ParsedAtom {
            id: lid,
            desc: format!("ListLink [{}\u{2192}{}]", words[0], words[1]),
            is_new: ln,
        });
        return ParseResult { atoms };
    }

    // Single word: concept
    let mut atoms = Vec::new();
    let (id, is_new) = space.add_node(AtomType::ConceptNode, words[0], TruthValue::new(0.80, 0.50));
    atoms.push(ParsedAtom {
        id,
        desc: format!("ConceptNode \"{}\"", words[0]),
        is_new,
    });
    ParseResult { atoms }
}

fn make_inheritance(space: &mut AtomSpace, subj: &str, obj: &str) -> ParseResult {
    let mut atoms = Vec::new();

    let (sid, sn) = space.add_node(AtomType::ConceptNode, subj, TruthValue::new(0.90, 0.85));
    atoms.push(ParsedAtom {
        id: sid,
        desc: format!("ConceptNode \"{}\"", subj),
        is_new: sn,
    });

    let (oid, on) = space.add_node(AtomType::ConceptNode, obj, TruthValue::new(0.90, 0.85));
    atoms.push(ParsedAtom {
        id: oid,
        desc: format!("ConceptNode \"{}\"", obj),
        is_new: on,
    });

    let (lid, ln) = space.add_link(
        AtomType::InheritanceLink,
        vec![sid, oid],
        TruthValue::new(0.95, 0.90),
    );
    atoms.push(ParsedAtom {
        id: lid,
        desc: format!("InheritanceLink [{}\u{2192}{}]", subj, obj),
        is_new: ln,
    });

    ParseResult { atoms }
}

fn make_evaluation(space: &mut AtomSpace, pred: &str, subj: &str, obj: &str) -> ParseResult {
    let mut atoms = Vec::new();

    let (sid, sn) = space.add_node(AtomType::ConceptNode, subj, TruthValue::new(0.80, 0.50));
    atoms.push(ParsedAtom {
        id: sid,
        desc: format!("ConceptNode \"{}\"", subj),
        is_new: sn,
    });

    let (oid, on) = space.add_node(AtomType::ConceptNode, obj, TruthValue::new(0.80, 0.50));
    atoms.push(ParsedAtom {
        id: oid,
        desc: format!("ConceptNode \"{}\"", obj),
        is_new: on,
    });

    let (pid, pn) = space.add_node(AtomType::PredicateNode, pred, TruthValue::new(0.70, 0.40));
    atoms.push(ParsedAtom {
        id: pid,
        desc: format!("PredicateNode \"{}\"", pred),
        is_new: pn,
    });

    let (lid, ln) = space.add_link(
        AtomType::ListLink,
        vec![sid, oid],
        TruthValue::new(0.0, 0.0),
    );
    atoms.push(ParsedAtom {
        id: lid,
        desc: format!("ListLink [{}\u{2192}{}]", subj, obj),
        is_new: ln,
    });

    let (eid, en) = space.add_link(
        AtomType::EvaluationLink,
        vec![pid, lid],
        TruthValue::new(0.70, 0.30),
    );
    atoms.push(ParsedAtom {
        id: eid,
        desc: format!("EvaluationLink [{}\u{2192}({},{})]", pred, subj, obj),
        is_new: en,
    });

    ParseResult { atoms }
}
