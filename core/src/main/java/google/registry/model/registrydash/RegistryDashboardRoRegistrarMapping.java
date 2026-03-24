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

package google.registry.model.registrydash;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

/** Maps RO (Registry Operator) users to the registrars they can view in the dashboard. */
@Entity
@Table(name = "RegistryDashboardRoRegistrarMapping")
public class RegistryDashboardRoRegistrarMapping {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(name = "user_email_address", nullable = false)
  String userEmailAddress;

  @Column(name = "registrar_id", nullable = false)
  String registrarId;

  @Column(name = "created_at", nullable = false)
  ZonedDateTime createdAt;

  /** Required by Hibernate. */
  public RegistryDashboardRoRegistrarMapping() {}

  public RegistryDashboardRoRegistrarMapping(String userEmailAddress, String registrarId) {
    this.userEmailAddress = userEmailAddress;
    this.registrarId = registrarId;
    this.createdAt = ZonedDateTime.now(java.time.ZoneOffset.UTC);
  }

  public Long getId() {
    return id;
  }

  public String getUserEmailAddress() {
    return userEmailAddress;
  }

  public String getRegistrarId() {
    return registrarId;
  }

  public ZonedDateTime getCreatedAt() {
    return createdAt;
  }
}
