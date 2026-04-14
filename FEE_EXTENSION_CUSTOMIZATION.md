# UD Fee Extension Customization vs Upstream

## Overview

This document explains the intentional differences between UD's Nomulus fork and the upstream Google repository regarding fee extension visibility and configuration.

**Status:** Implemented and tested (PR #56, build: `db4f84f5-bf6e-49cd-a889-d191bc7450cf`)

---

## The Discrepancy

### Upstream Behavior
Upstream Google Nomulus advertises **ALL** fee extension versions in **ALL** environments:
- `urn:ietf:params:xml:ns:fee-0.6` (draft 0.6)
- `urn:ietf:params:xml:ns:fee-0.11` (draft 0.11)
- `urn:ietf:params:xml:ns:fee-0.12` (draft 0.12)
- `urn:ietf:params:xml:ns:epp:fee-1.0` (final 1.0)

**FEE_1_00 conditional logic:** Uses a FeatureFlag to optionally enable FEE_1_00 in production

### UD Customization
UD's Nomulus only advertises the **final fee-1.0 version in non-production environments**:
- Draft versions (0.6, 0.11, 0.12): **Hidden** (to avoid IANA registry warnings)
- FEE_1_00: **Only visible in non-production**
- Production: **No fee extensions advertised**

---

## Why This Customization

### 1. Avoid IANA Registry Warnings
The ICANN (IANA) registry monitoring system flags draft/experimental extensions as non-standard. By hiding draft versions, UD avoids compliance issues and audit warnings.

### 2. Production Safety
UD's production environment intentionally does NOT advertise fee extensions, ensuring registrants cannot be surprised by undocumented fees at production rollout.

### 3. Simplified Logic
Rather than using FeatureFlags (which require database transactions), UD uses simple environment checks:
- If NOT production → advertise FEE_1_00
- If production → hide FEE_1_00

This is more predictable and testable.

---

## Files Changed and Why

### Core Source Files

#### 1. `core/src/main/java/google/registry/model/eppcommon/ProtocolDefinition.java`
**Changes:**
- Removed static imports: `FeatureFlag`, `TransactionManagerFactory.tm()`
- Changed FEE_0_6, FEE_0_11, FEE_0_12 visibility from `ALL` to `NONE`
- Simplified FEE_1_00 logic: removed FeatureFlag check

**Why:**
- Hide draft fee extensions to avoid IANA warnings
- Remove FeatureFlag dependency (simpler, more predictable behavior)
- All comments in code explain this rationale

#### 2. `core/src/main/java/google/registry/model/smd/SignedMarkRevocationList.java`
**Changes:**
- Added `import google.registry.tmch.RstTmchUtils`
- Added overloaded `get(String tld)` method for TLD-specific SMDR retrieval

**Why:**
- Support ICANN RST (Registry Services Technical) compliance
- Allow per-TLD configuration of Signed Mark Data Record lists
- Comment in code links to issue: `TODO(b/412715713): remove the tld parameter when RST completes`

#### 3. Other source files (SecretManagerKeyring, SecretManagerModule, KeyringModule)
**Status:** ✅ **No changes** - Perfectly aligned with upstream

---

### Test Files

#### `core/src/test/java/google/registry/flows/FlowTestCase.java`
**Changes:**
- Added `addServiceExtensionUri(String uri)` helper method

**Why:**
- Tests that use draft fee extensions (now hidden) must explicitly declare them
- This helper allows test setup to register hidden extensions
- Comment in method explains the purpose

#### All Domain Flow Tests (DomainCheckFlowTest, DomainCreateFlowTest, etc.)
**Changes:**
- Added `import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension`
- Added `addServiceExtensionUri(ServiceExtension.FEE_0_6.getUri())` calls to fee extension tests

**Why:**
- Test XML files use `fee-0.6` namespace in payload
- Since FEE_0_6 is now hidden (visibility.NONE), tests must declare it at login
- Without declaration, tests fail with `UndeclaredServiceExtensionException`
- Comment in each test explains why this is needed

#### `core/src/test/java/google/registry/flows/domain/ProductionSimulatingFeeExtensionsTest.java`
**Changes:**
- Updated `testProdEnvironment()` to NOT expect FEE_1_00 in production
- Updated comment to explain that FEE_1_00 is conditionally visible (non-prod only)

**Why:**
- Test assertions must match actual behavior
- FEE_1_00 is now NOT visible in production (by design)
- Comment clarifies the UD customization intent

---

## Merge Conflict Resolution Guide

### When Merging Upstream Changes

If you encounter merge conflicts in these files, here's how to resolve them:

#### ProtocolDefinition.java
**Conflict Risk:** HIGH
- Upstream may update FEE extension handling
- **Resolution:** Keep UD customization (NONE visibility for drafts)
- **Do NOT revert** to `ServiceExtensionVisibility.ALL` for FEE_0_6/0_11/0_12
- **Do NOT re-add** FeatureFlag logic for FEE_1_00

#### Test Files (Domain*FlowTest.java)
**Conflict Risk:** MEDIUM
- Upstream may add new test cases
- **If new test uses fee extensions:** Add `addServiceExtensionUri()` call
- **Pattern:** Look for XML with `xmlns:fee=` → add corresponding `addServiceExtensionUri()` call
- **Do NOT remove** existing `addServiceExtensionUri()` calls

#### SignedMarkRevocationList.java
**Conflict Risk:** LOW
- UD's RST changes are isolated (new method, new import)
- **Resolution:** Keep UD's `get(String tld)` method alongside upstream changes

### Detecting the Issue
If tests fail with these symptoms, check for incomplete merge:
1. `UndeclaredServiceExtensionException` → Missing `addServiceExtensionUri()` call
2. Fee extensions visible in production → Visibility reverted to `ALL`
3. Static imports causing circular dependency errors → FeatureFlag/TransactionManagerFactory imports re-added

---

## Testing

All changes are tested and verified:
- ✅ 5241 tests passing (Cloud Build: `db4f84f5-bf6e-49cd-a889-d191bc7450cf`)
- ✅ `ProductionSimulatingFeeExtensionsTest` validates visibility behavior
- ✅ Fee extension tests in all domain flows validate functionality

### Verification Commands
```bash
# Run fee extension visibility tests
./gradlew :core:test --tests ProductionSimulatingFeeExtensionsTest

# Run specific domain flow tests
./gradlew :core:test --tests DomainInfoFlowTest
```

---

## References

- **PR #56:** Initial implementation of this customization
- **RST Issue:** `b/412715713` - SignedMarkRevocationList TLD parameter
- **IANA Compliance:** Draft fee extensions cause registry warnings
- **Upstream:** Google Nomulus repository (`google/nomulus`)
