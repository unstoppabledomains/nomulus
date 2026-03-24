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
import google.registry.model.registrydash.RegistryDashboardRoRegistrarMapping;
import java.util.List;

/** Utility for scoping registry dashboard data access to an RO user's mapped registrars. */
public final class RegistryDashAccessUtil {

  private static final String MAPPING_QUERY =
      """
      SELECT m FROM RegistryDashboardRoRegistrarMapping m
      WHERE m.userEmailAddress = :email
      """;

  private RegistryDashAccessUtil() {}

  /** Returns the set of registrar IDs that the given user email is mapped to. */
  public static ImmutableSet<String> getMappedRegistrarIds(String userEmail) {
    return tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardRoRegistrarMapping> mappings =
              tm().getEntityManager()
                  .createQuery(MAPPING_QUERY, RegistryDashboardRoRegistrarMapping.class)
                  .setParameter("email", userEmail)
                  .getResultList();
          return mappings.stream()
              .map(RegistryDashboardRoRegistrarMapping::getRegistrarId)
              .collect(ImmutableSet.toImmutableSet());
        });
  }
}
