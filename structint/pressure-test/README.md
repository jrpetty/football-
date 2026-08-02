# Water pressure functional tests

`src/` is a deterministic stand-in for the slice of Minecraft the pressure engine touches, plus
test doubles for the mod's own `StructuralData` / `ManagedBlocks` / `BlockClassifier` / `Config`.
`harness/Test.java` builds actual dams in it and asserts the arithmetic.

    ./run.sh ../src/main/java /path/to/fastutil.jar

Covers: the wooden dam bursting at the base, stone thickness thresholds, arch action raising
capacity, buttresses lending by distance and falling out of reach, water cancelling on both sides,
runs anchoring into natural terrain, an underwater base relieved by flooding the room, pond versus
ocean body factor, and a breach cascading across sweeps.

These fakes are not Minecraft — the tests pin down the engine's logic, not its API bindings. The
bindings are covered separately by diffing emitted method descriptors against the original jars.
