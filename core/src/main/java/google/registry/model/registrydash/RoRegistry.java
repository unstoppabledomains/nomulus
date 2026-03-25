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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A named registry group that owns TLDs and has users. */
@Entity
@Table(name = "RoRegistry")
public class RoRegistry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false, unique = true)
  String name;

  @Column(name = "created_at", nullable = false)
  ZonedDateTime createdAt;

  // ===== start registry-dash-financials =====
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  String settings = "{}";
  // ===== end registry-dash-financials =====

  /** Required by Hibernate. */
  public RoRegistry() {}

  public RoRegistry(String name) {
    this.name = name;
    this.createdAt = ZonedDateTime.now(ZoneOffset.UTC);
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public ZonedDateTime getCreatedAt() {
    return createdAt;
  }

  public String getSettings() {
    return settings;
  }

  public void setSettings(String settings) {
    this.settings = settings;
  }
}
