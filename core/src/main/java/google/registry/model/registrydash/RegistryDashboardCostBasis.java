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
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;

/** RO cost basis — what the RSP pays upstream to the RO per TLD (dashboard-only). */
@Entity
@Table(name = "RegistryDashboardCostBasis")
public class RegistryDashboardCostBasis {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  String tld;

  @Column(nullable = false)
  String operation;

  @Column(name = "cost_amount", nullable = false, precision = 19, scale = 2)
  BigDecimal costAmount;

  @Column(name = "cost_currency", nullable = false)
  String costCurrency;

  @Column(name = "effective_date", nullable = false)
  ZonedDateTime effectiveDate;

  @Nullable
  @Column
  String notes;

  @Column(name = "created_at", nullable = false)
  ZonedDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  ZonedDateTime updatedAt;

  /** Required by Hibernate. */
  public RegistryDashboardCostBasis() {
    ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
    this.createdAt = now;
    this.updatedAt = now;
  }

  public Long getId() {
    return id;
  }

  public String getTld() {
    return tld;
  }

  public void setTld(String tld) {
    this.tld = tld;
  }

  public String getOperation() {
    return operation;
  }

  public void setOperation(String operation) {
    this.operation = operation;
  }

  public BigDecimal getCostAmount() {
    return costAmount;
  }

  public void setCostAmount(BigDecimal costAmount) {
    this.costAmount = costAmount;
  }

  public String getCostCurrency() {
    return costCurrency;
  }

  public void setCostCurrency(String costCurrency) {
    this.costCurrency = costCurrency;
  }

  public ZonedDateTime getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(ZonedDateTime effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  @Nullable
  public String getNotes() {
    return notes;
  }

  public void setNotes(@Nullable String notes) {
    this.notes = notes;
  }

  public ZonedDateTime getCreatedAt() {
    return createdAt;
  }

  public ZonedDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(ZonedDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
