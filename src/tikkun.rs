//! Tikkun — self-repair diagnostics
//! Verifies AtomSpace integrity: valid TVs, no orphans, type diversity.

use crate::atom::AtomType;
use crate::atomspace::AtomSpace;

pub struct TikkunCheck {
    pub name: String,
    pub passed: bool,
    pub detail: Option<String>,
}

pub struct TikkunReport {
    pub checks: Vec<TikkunCheck>,
    pub all_healthy: bool,
}

pub fn run_tikkun(space: &AtomSpace) -> TikkunReport {
    let mut checks = Vec::new();

    // 1. atoms > 0
    let count = space.size();
    checks.push(TikkunCheck {
        name: "atoms>0".into(),
        passed: count > 0,
        detail: Some(format!("{} atoms", count)),
    });

    // 2. all truth values valid
    let invalid_tvs = space.all_atoms_sorted().iter().filter(|a| !a.tv.is_valid()).count();
    checks.push(TikkunCheck {
        name: "tvs-valid".into(),
        passed: invalid_tvs == 0,
        detail: if invalid_tvs > 0 {
            Some(format!("{} invalid", invalid_tvs))
        } else {
            None
        },
    });

    // 3. no orphan links (links referencing nonexistent atoms)
    let orphans: usize = space
        .all_atoms_sorted()
        .iter()
        .filter(|a| a.atom_type.is_link())
        .flat_map(|a| &a.outgoing)
        .filter(|&&id| space.get(id).is_none())
        .count();
    checks.push(TikkunCheck {
        name: "no-orphans".into(),
        passed: orphans == 0,
        detail: if orphans > 0 {
            Some(format!("{} orphan refs", orphans))
        } else {
            None
        },
    });

    // 4. type diversity (at least 2 distinct types)
    let types = space.types_present();
    checks.push(TikkunCheck {
        name: "has-types".into(),
        passed: types.len() >= 2,
        detail: Some(format!("{} types", types.len())),
    });

    // 5. no self-inheritance loops
    let self_loops = space
        .get_by_type(AtomType::InheritanceLink)
        .iter()
        .filter_map(|&id| space.get(id))
        .filter(|a| a.outgoing.len() == 2 && a.outgoing[0] == a.outgoing[1])
        .count();
    checks.push(TikkunCheck {
        name: "no-self-inherit".into(),
        passed: self_loops == 0,
        detail: if self_loops > 0 {
            Some(format!("{} self-loops", self_loops))
        } else {
            None
        },
    });

    let all_healthy = checks.iter().all(|c| c.passed);
    TikkunReport {
        checks,
        all_healthy,
    }
}
