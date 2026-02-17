# Example trace output

Captured with `scripts/demo-run.sh` (port 8451, `TRACE_INPUT="penguin is-a bird"`), the JSON below illustrates the Coggy PARSE→GROUND→ATTEND→INFER→REFLECT trace + focus snapshot that powers dashboards and prompts.

```json
{
  "event": "trace",
  "focus": [
    {"atom": "ConceptNode:\"bird\"", "sti": 43.1},
    {"atom": "ConceptNode:\"penguin\"", "sti": 43.1},
    {"atom": "InheritanceLink:[penguin→bird]", "sti": 38.56}
  ],
  "trace": [
    {"phase": "PARSE → NL→Atomese", "lines": ["2 atoms produced", "⊕ ConceptNode \"penguin\"", "○ ConceptNode \"bird\""]},
    {"phase": "GROUND → ontology lookup", "lines": ["○ (ConceptNode \"penguin\") NOT FOUND — 0 links", "⊕ (ConceptNode \"bird\") GROUNDED — 2 ontology links"]},
    {"phase": "ATTEND → STI spread", "lines": ["★ ConceptNode:\"bird\": STI 0→43.1", "★ ConceptNode:\"penguin\": STI 0→43.1"]},
    {"phase": "INFER → PLN forward chain (depth 2) — 32 inferences", "lines": ["⊢ InheritanceLink:[penguin→animal] ← deduction ..."]},
    {"phase": "REFLECT → trace summary", "lines": ["New atoms: 34  |  Inferred: 32"]}
  ]
}
```

Drop this snippet into a breadcrumb or prompt when you need to demonstrate a successful smoke run.
