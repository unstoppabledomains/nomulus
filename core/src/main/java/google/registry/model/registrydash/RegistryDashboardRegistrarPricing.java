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

/** Per-registrar pricing rules, read by DomainPricingCustomLogic at EPP time. */
@Entity
@Table(name = "RegistryDashboardRegistrarPricing")
public class RegistryDashboardRegistrarPricing {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(name = "registrar_id", nullable = false)
  String registrarId;

  @Column(nullable = false)
  String tld;

  @Column(nullable = false)
  String operation;

  @Column(name = "price_amount", nullable = false, precision = 19, scale = 2)
  BigDecimal priceAmount;

  @Column(name = "price_currency", nullable = false)
  String priceCurrency;

  @Column(name = "effective_date", nullable = false)
  ZonedDateTime effectiveDate;

  @Nullable
  @Column(name = "expiry_date")
  ZonedDateTime expiryDate;

  @Column(name = "is_active", nullable = false)
  boolean isActive;

  @Column(name = "created_at", nullable = false)
  ZonedDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  ZonedDateTime updatedAt;

  /** Required by Hibernate. */
  public RegistryDashboardRegistrarPricing() {
    ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
    this.createdAt = now;
    this.updatedAt = now;
  }

  public Long getId() {
    return id;
  }

  public String getRegistrarId() {
    return registrarId;
  }

  public void setRegistrarId(String registrarId) {
    this.registrarId = registrarId;
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

  public BigDecimal getPriceAmount() {
    return priceAmount;
  }

  public void setPriceAmount(BigDecimal priceAmount) {
    this.priceAmount = priceAmount;
  }

  public String getPriceCurrency() {
    return priceCurrency;
  }

  public void setPriceCurrency(String priceCurrency) {
    this.priceCurrency = priceCurrency;
  }

  public ZonedDateTime getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(ZonedDateTime effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  @Nullable
  public ZonedDateTime getExpiryDate() {
    return expiryDate;
  }

  public void setExpiryDate(@Nullable ZonedDateTime expiryDate) {
    this.expiryDate = expiryDate;
  }

  public boolean isActive() {
    return isActive;
  }

  public void setActive(boolean active) {
    this.isActive = active;
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
