// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console.registrydash;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrydash.RoRegistry;
import google.registry.model.registrydash.RoRegistryTld;
import google.registry.model.registrydash.RoRegistryUser;
import java.util.List;
import java.util.Optional;

/**
 * Utility for scoping registry dashboard data access.
 *
 * <p>Access chain: user -> RoRegistryUser -> RoRegistry -> RoRegistryTld -> TLDs -> Registrars.
 */
public final class RegistryDashAccessUtil {

  private static final String TLDS_FOR_USER =
      """
      SELECT rt FROM RoRegistryTld rt
      WHERE rt.registryId IN (
        SELECT ru.registryId FROM RoRegistryUser ru
        WHERE ru.userEmail = :email
      )
      """;

  private static final String REGISTRARS_BY_TYPE =
      "SELECT r FROM Registrar r WHERE r.type = :type";

  private RegistryDashAccessUtil() {}

  /** Returns the set of TLDs accessible to the given user via their registry memberships. */
  public static ImmutableSet<String> getMappedTlds(String userEmail) {
    return tm().transact(
        () -> {
          List<RoRegistryTld> tldMappings =
              tm().getEntityManager()
                  .createQuery(TLDS_FOR_USER, RoRegistryTld.class)
                  .setParameter("email", userEmail)
                  .getResultList();
          return tldMappings.stream()
              .map(RoRegistryTld::getTld)
              .collect(ImmutableSet.toImmutableSet());
        });
  }

  /** Returns registrar IDs that have at least one allowedTld overlapping with the given TLDs. */
  public static ImmutableSet<String> getRegistrarIdsForTlds(ImmutableSet<String> tlds) {
    if (tlds.isEmpty()) {
      return ImmutableSet.of();
    }
    return tm().transact(
        () -> {
          List<Registrar> registrars =
              tm().getEntityManager()
                  .createQuery(REGISTRARS_BY_TYPE, Registrar.class)
                  .setParameter("type", Registrar.Type.REAL)
                  .getResultList();
          return registrars.stream()
              .filter(r -> r.getAllowedTlds().stream().anyMatch(tlds::contains))
              .map(Registrar::getRegistrarId)
              .collect(ImmutableSet.toImmutableSet());
        });
  }

  /** Convenience: get registrar IDs for a user by looking up their TLDs first. */
  public static ImmutableSet<String> getMappedRegistrarIds(String userEmail) {
    ImmutableSet<String> tlds = getMappedTlds(userEmail);
    return getRegistrarIdsForTlds(tlds);
  }

  /** Returns the RoRegistry for a user, if any. A non-FTE user belongs to at most one registry. */
  public static Optional<RoRegistry> getRegistryForUser(String userEmail) {
    return tm().transact(
        () -> {
          List<RoRegistryUser> users =
              tm().getEntityManager()
                  .createQuery(
                      "SELECT ru FROM RoRegistryUser ru WHERE ru.userEmail = :email",
                      RoRegistryUser.class)
                  .setParameter("email", userEmail)
                  .getResultList();
          if (users.isEmpty()) {
            return Optional.empty();
          }
          RoRegistry registry =
              tm().getEntityManager().find(RoRegistry.class, users.get(0).getRegistryId());
          return Optional.ofNullable(registry);
        });
  }
}
