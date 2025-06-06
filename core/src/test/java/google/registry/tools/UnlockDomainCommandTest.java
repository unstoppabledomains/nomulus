// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentRegistryLockByRepoId;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.Domain;
import google.registry.model.domain.RegistryLock;
import google.registry.model.registrar.Registrar.Type;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.util.StringGenerator.Alphabets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UnlockDomainCommand}. */
class UnlockDomainCommandTest extends CommandTestCase<UnlockDomainCommand> {

  @BeforeEach
  void beforeEach() {
    persistNewRegistrar("adminreg", "Admin Registrar", Type.REAL, 693L);
    createTld("tld");
    command.registryAdminClientId = "adminreg";
    command.domainLockUtils =
        new DomainLockUtils(
            new DeterministicStringGenerator(Alphabets.BASE_58),
            "adminreg",
            new CloudTasksHelper(fakeClock).getTestCloudTasksUtils());
    command.printStream = System.out;
  }

  private Domain persistLockedDomain(String domainName, String registrarId) {
    Domain domain = persistResource(DatabaseHelper.newDomain(domainName));
    RegistryLock lock =
        command.domainLockUtils.saveNewRegistryLockRequest(domainName, registrarId, null, true);
    command.domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), true);
    return reloadResource(domain);
  }

  @Test
  void testSuccess_unlocksDomain() throws Exception {
    Domain domain = persistLockedDomain("example.tld", "TheRegistrar");
    runCommandForced("--client=TheRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  void testSuccess_partiallyUpdatesStatuses() throws Exception {
    Domain domain = persistLockedDomain("example.tld", "TheRegistrar");
    domain =
        persistResource(
            domain
                .asBuilder()
                .setStatusValues(
                    ImmutableSet.of(SERVER_DELETE_PROHIBITED, SERVER_UPDATE_PROHIBITED))
                .build());
    runCommandForced("--client=TheRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  void testSuccess_manyDomains() throws Exception {
    // Create 26 domains -- one more than the number of entity groups allowed in a transaction (in
    // case that was going to be the failure point).
    List<Domain> domains = new ArrayList<>();
    for (int n = 0; n < 26; n++) {
      String domain = String.format("domain%d.tld", n);
      domains.add(persistLockedDomain(domain, "TheRegistrar"));
    }
    runCommandForced(
        ImmutableList.<String>builder()
            .add("--client=TheRegistrar")
            .addAll(domains.stream().map(Domain::getDomainName).collect(Collectors.toList()))
            .build());
    for (Domain domain : domains) {
      assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    }
  }

  @Test
  void testFailure_domainDoesntExist() throws Exception {
    runCommandForced("--client=NewRegistrar", "missing.tld");
    assertInStdout("Failed domains:\n[missing.tld (Domain doesn't exist)]");
  }

  @Test
  void testSuccess_alreadyUnlockedDomain_staysUnlocked() throws Exception {
    Domain domain = persistActiveDomain("example.tld");
    runCommandForced("--client=TheRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  void testSuccess_defaultsToAdminRegistrar_ifUnspecified() throws Exception {
    Domain domain = persistLockedDomain("example.tld", "TheRegistrar");
    runCommandForced("example.tld");
    assertThat(getMostRecentRegistryLockByRepoId(domain.getRepoId()).get().getRegistrarId())
        .isEqualTo("adminreg");
  }

  @Test
  void testFailure_duplicateDomainsAreSpecified() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=TheRegistrar", "dupe.tld", "dupe.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate domain arguments found: 'dupe.tld'");
  }
}
