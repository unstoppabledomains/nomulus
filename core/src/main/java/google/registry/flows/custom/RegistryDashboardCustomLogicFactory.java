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

package google.registry.flows.custom;

import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.model.eppinput.EppInput;
import java.util.Optional;

/**
 * Custom logic factory that returns {@link RegistryDashboardPricingCustomLogic} for domain pricing,
 * enabling per-registrar pricing from the registry dashboard tables.
 */
public class RegistryDashboardCustomLogicFactory extends CustomLogicFactory {

  @Override
  public DomainPricingCustomLogic forDomainPricing(
      Optional<EppInput> eppInput,
      Optional<SessionMetadata> sessionMetadata,
      Optional<FlowMetadata> flowMetadata) {
    return new RegistryDashboardPricingCustomLogic(
        eppInput.orElse(null), sessionMetadata.orElse(null), flowMetadata.orElse(null));
  }
}
