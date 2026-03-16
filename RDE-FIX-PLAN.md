# Plan: Fix RDE Deposit - Conditional IDN + Add eppParams

## Context

ICANN RST tests `rde-05` and `rde-13` fail because Nomulus generates incomplete RDE deposit XML:
1. **IDN URI included unconditionally** - even for TLDs with no IDN tables (violates RST spec)
2. **Missing `rdeEppParams`** - no enum entry, no code to generate the `<rdeEppParams:eppParams>` element

Upstream `google/nomulus` has NOT fixed either issue. Our changes target only files untouched by upstream, ensuring **zero merge conflict risk**.

### Merge Conflict Analysis

| File | Upstream Changed? | Our Plan |
|------|:-:|----------|
| `RdeResourceType.java` | No | Modify |
| `RdeIO.java` | No | Modify |
| `RdeMarshaller.java` | No | Modify |
| `RdeCounter.java` | No | No changes (auto-handles new enum) |
| `ProtocolDefinition.java` | **Yes** | Read only (use existing data) |
| `DomainToXjcConverter.java` | **Yes** | Do not touch |
| `ContactToXjcConverter.java` | **Yes** (deleted) | Do not touch |

### Required eppParams Content (from live greeting)

Must exactly match `test-inputs/prod/files/greeting.xml`:
- **objURIs**: host-1.0, domain-1.0, contact-1.0
- **svcExtension**: launch-1.0, rgp-1.0, secDNS-1.1, fee-1.0
- **DCP**: access=all, purpose=admin+prov, recipient=ours+public, retention=**indefinite**

This matches our current `ProtocolDefinition.java` + `Greeting.java`.

---

## Step 1: Add `EPPPARAMS` to `RdeResourceType.java`

**File:** `core/src/main/java/google/registry/rde/RdeResourceType.java`

1. Add new enum entry (before HEADER):
```java
EPPPARAMS("urn:ietf:params:xml:ns:rdeEppParams-1.0", EnumSet.of(FULL)),
```

2. Add overloaded `getUris()` for conditional IDN:
```java
public static ImmutableSortedSet<String> getUris(RdeMode mode, boolean includeIdn) {
  ImmutableSortedSet.Builder<String> builder =
      new ImmutableSortedSet.Builder<>(Ordering.natural());
  for (RdeResourceType resourceType : RdeResourceType.values()) {
    if (resourceType.getModes().contains(mode)) {
      if (resourceType == IDN && !includeIdn) {
        continue;
      }
      builder.add(resourceType.getUri());
    }
  }
  return builder.build();
}
```

3. Update existing `getUris(RdeMode)` to delegate: `return getUris(mode, true);`

## Step 2: Add `marshalEppParams()` to `RdeMarshaller.java`

**File:** `core/src/main/java/google/registry/rde/RdeMarshaller.java`

Add method that builds XjcRdeEppParams using `ProtocolDefinition` data (matching the live EPP greeting exactly):

```java
public String marshalEppParams() {
  XjcRdeEppParams eppParams = new XjcRdeEppParams();
  eppParams.getVersions().add(ProtocolDefinition.VERSION);
  eppParams.getLangs().add(ProtocolDefinition.LANGUAGE);

  // objURIs - from ProtocolDefinition.SUPPORTED_OBJECT_SERVICES
  for (String objUri : ProtocolDefinition.SUPPORTED_OBJECT_SERVICES) {
    eppParams.getObjURIs().add(objUri);
  }

  // Service extensions - visible ones matching greeting
  XjcEppExtURIType svcExtension = new XjcEppExtURIType();
  for (String extUri : ProtocolDefinition.getVisibleServiceExtensionUris()) {
    svcExtension.getExtURIs().add(extUri);
  }
  eppParams.setSvcExtension(svcExtension);

  // DCP - must match Greeting.java exactly
  // access=all, purpose=admin+prov, recipient=ours+public, retention=indefinite
  XjcEppDcpType dcp = new XjcEppDcpType();
  // ... (build DCP matching Greeting.java structure)
  eppParams.setDcp(dcp);

  return marshalOrDie(new XjcRdeEppParamsElement(eppParams));
}
```

**Imports needed:** `ProtocolDefinition`, `XjcRdeEppParams`, `XjcRdeEppParamsElement`, `XjcEppExtURIType`, `XjcEppDcpType` and related DCP types.

**Implementation note:** Need to examine the XJC-generated DCP classes (`XjcEppDcpAccessType`, `XjcEppDcpStatementType`, `XjcEppDcpPurposeType`, `XjcEppDcpRecipientType`, `XjcEppDcpRetentionType`) during implementation for exact setter/field signatures (these are generated classes - check `core/build/generated/sources/custom/java/main/google/registry/xjc/epp/`).

## Step 3: Modify `RdeIO.java` - conditional IDN + eppParams

**File:** `core/src/main/java/google/registry/beam/rde/RdeIO.java`

### 3a: Load TLD entity early in `processElement()` (~line 165)
```java
Tld tldEntity = Tld.get(tld);
boolean hasIdnTables = !tldEntity.getIdnTables().isEmpty();
```

### 3b: Conditional IDN in header URIs (line ~201)
```java
// Replace RdeResourceType.getUris(mode) with:
RdeResourceType.getUris(mode, hasIdnTables)
```

### 3c: Conditional IDN elements (lines ~216-221)
Only write IDN tables that are explicitly configured for the TLD:
```java
if (mode == RdeMode.FULL && hasIdnTables) {
  for (IdnTableEnum idn : tldEntity.getIdnTables()) {
    output.write(marshaller.marshalIdn(idn.getTable()));
    counter.increment(RdeResourceType.IDN);
  }
}
```

### 3d: Add eppParams element (after IDN block, before header element)
```java
if (mode == RdeMode.FULL) {
  output.write(marshaller.marshalEppParams());
  counter.increment(RdeResourceType.EPPPARAMS);
}
```

### 3e: New imports
- `google.registry.model.tld.Tld`

## Step 4: Update Tests

Search for and update affected tests:
- Tests asserting on `RdeResourceType.getUris()` output
- Tests asserting on deposit XML content (URI lists, element presence)
- The test fixture `core/src/test/resources/google/registry/rde/deposit_full.xml` already contains eppParams (lines 170-201) which is good
- `RdeCounter` tests if they assert on specific resource type sets

---

## Verification

1. **Build:** `./nom_build :core:compileJava`
2. **RDE tests:** `./nom_build :core:test --tests "*Rde*"`
3. **Specific validations:**
   - eppParams element appears in FULL deposit with correct content matching greeting
   - IDN excluded when `Tld.getIdnTables()` returns empty
   - IDN included (only configured tables) when TLD has explicit IDN tables
   - eppParams objURI order matches greeting: host-1.0, domain-1.0, contact-1.0
   - eppParams extURI includes: launch-1.0, rgp-1.0, secDNS-1.1, fee-1.0
4. **Full suite:** `./nom_build :core:test` for regressions

## Key Files

| Role | Path |
|------|------|
| Resource type enum | `core/src/main/java/google/registry/rde/RdeResourceType.java` |
| Deposit writer | `core/src/main/java/google/registry/beam/rde/RdeIO.java` |
| XML marshaller | `core/src/main/java/google/registry/rde/RdeMarshaller.java` |
| Header counter | `core/src/main/java/google/registry/rde/RdeCounter.java` |
| EPP protocol defs | `core/src/main/java/google/registry/model/eppcommon/ProtocolDefinition.java` |
| EPP greeting | `core/src/main/java/google/registry/model/eppoutput/Greeting.java` |
| TLD model | `core/src/main/java/google/registry/model/tld/Tld.java` |
| XJC EppParams | `core/build/generated/.../xjc/rdeeppparams/XjcRdeEppParams.java` |
| XJC DCP types | `core/build/generated/.../xjc/epp/XjcEppDcp*.java` |
| Live greeting ref | `rsp-2025/icann-rst/rst-api-client/test-inputs/prod/files/greeting.xml` |
| Test fixture | `core/src/test/resources/google/registry/rde/deposit_full.xml` |
