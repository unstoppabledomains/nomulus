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

/** Maps registry users to the TLDs they operate. Registrar access is derived from TLD. */
@Entity
@Table(name = "RegistryDashboardRoTldMapping")
public class RegistryDashboardRoTldMapping {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(name = "user_email_address", nullable = false)
  String userEmailAddress;

  @Column(name = "tld", nullable = false)
  String tld;

  @Column(name = "created_at", nullable = false)
  ZonedDateTime createdAt;

  /** Required by Hibernate. */
  public RegistryDashboardRoTldMapping() {}

  public RegistryDashboardRoTldMapping(String userEmailAddress, String tld) {
    this.userEmailAddress = userEmailAddress;
    this.tld = tld;
    this.createdAt = ZonedDateTime.now(java.time.ZoneOffset.UTC);
  }

  public Long getId() { return id; }
  public String getUserEmailAddress() { return userEmailAddress; }
  public String getTld() { return tld; }
  public ZonedDateTime getCreatedAt() { return createdAt; }
}
