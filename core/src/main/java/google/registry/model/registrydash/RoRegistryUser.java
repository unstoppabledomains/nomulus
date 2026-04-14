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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Assigns a user to a registry. */
@Entity
@Table(name = "RoRegistryUser")
public class RoRegistryUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(name = "registry_id", nullable = false)
  Long registryId;

  @Column(name = "user_email", nullable = false)
  String userEmail;

  @Column(name = "created_at", nullable = false)
  ZonedDateTime createdAt;

  /** Required by Hibernate. */
  public RoRegistryUser() {}

  public RoRegistryUser(Long registryId, String userEmail) {
    this.registryId = registryId;
    this.userEmail = userEmail;
    this.createdAt = ZonedDateTime.now(ZoneOffset.UTC);
  }

  public Long getId() {
    return id;
  }

  public Long getRegistryId() {
    return registryId;
  }

  public String getUserEmail() {
    return userEmail;
  }

  public ZonedDateTime getCreatedAt() {
    return createdAt;
  }
}
