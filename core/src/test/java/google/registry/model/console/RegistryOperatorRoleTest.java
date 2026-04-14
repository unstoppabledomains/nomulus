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

package google.registry.model.console;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for the REGISTRY_OPERATOR global role and its permissions. */
class RegistryOperatorRoleTest {

  @Test
  void testRegistryOperator_hasDashboardPermissions() {
    UserRoles userRoles =
        new UserRoles.Builder().setGlobalRole(GlobalRole.REGISTRY_OPERATOR).build();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_REGISTRAR_PORTFOLIO)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_PRICING)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.MANAGE_PRICING)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)).isTrue();
  }

  // UD: Registry Dashboard — REGISTRY_OPERATOR should NOT have registrar-facing permissions
  @Test
  void testRegistryOperator_doesNotHaveRegistrarPermissions() {
    UserRoles userRoles =
        new UserRoles.Builder().setGlobalRole(GlobalRole.REGISTRY_OPERATOR).build();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_REGISTRARS)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_REGISTRAR_DETAILS)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.DOWNLOAD_DOMAINS)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_TLD_PORTFOLIO)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.ACCESS_BILLING_DETAILS)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_OPERATIONAL_DATA)).isFalse();
  }

  // UD: Verify FTE still has perms after REGISTRY_OPERATOR tightening
  @Test
  void testFte_stillHasRegistrarPermissions() {
    UserRoles userRoles = new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_REGISTRARS)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_REGISTRAR_DETAILS)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.DOWNLOAD_DOMAINS)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.ACCESS_BILLING_DETAILS)).isTrue();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.VIEW_OPERATIONAL_DATA)).isTrue();
  }

  @Test
  void testRegistryOperator_doesNotHaveAdminPermissions() {
    UserRoles userRoles =
        new UserRoles.Builder().setGlobalRole(GlobalRole.REGISTRY_OPERATOR).build();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.MANAGE_REGISTRARS)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.MANAGE_USERS)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.SUSPEND_DOMAIN)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.EXECUTE_EPP_COMMANDS)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.CHANGE_NOMULUS_PASSWORD)).isFalse();
    assertThat(userRoles.hasGlobalPermission(ConsolePermission.EDIT_REGISTRAR_DETAILS)).isFalse();
  }

  @Test
  void testRegistryOperator_isNotAdmin() {
    UserRoles userRoles =
        new UserRoles.Builder().setGlobalRole(GlobalRole.REGISTRY_OPERATOR).build();
    assertThat(userRoles.isAdmin()).isFalse();
  }
}
