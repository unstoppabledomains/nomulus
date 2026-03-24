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
import google.registry.model.registrydash.RegistryDashboardRoTldMapping;
import java.util.List;

/** Utility for scoping registry dashboard data access via TLD mappings. */
public final class RegistryDashAccessUtil {

  private static final String TLD_MAPPING_QUERY =
      """
      SELECT m FROM RegistryDashboardRoTldMapping m
      WHERE m.userEmailAddress = :email
      """;

  private static final String REGISTRARS_BY_TLD =
      """
      SELECT r FROM Registrar r
      WHERE r.type = :type
      """;

  private RegistryDashAccessUtil() {}

  /** Returns the set of TLDs that the given user email is mapped to. */
  public static ImmutableSet<String> getMappedTlds(String userEmail) {
    return tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardRoTldMapping> mappings =
              tm().getEntityManager()
                  .createQuery(TLD_MAPPING_QUERY, RegistryDashboardRoTldMapping.class)
                  .setParameter("email", userEmail)
                  .getResultList();
          return mappings.stream()
              .map(RegistryDashboardRoTldMapping::getTld)
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
                  .createQuery(REGISTRARS_BY_TLD, Registrar.class)
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
}
