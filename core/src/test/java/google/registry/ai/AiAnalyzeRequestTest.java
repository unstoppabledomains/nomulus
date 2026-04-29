// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ai;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class AiAnalyzeRequestTest {

  private static AiAnalyzeRequest validRequestForPage(String page) {
    AiAnalyzeRequest req = new AiAnalyzeRequest();
    req.page = page;
    req.promptType = "summarize_trends";
    req.chartData = new JsonObject();
    return req;
  }

  @Test
  void validPages_includesAllTier1AndTier2() {
    assertThat(AiAnalyzeRequest.VALID_PAGES)
        .containsExactly(
            "domain-activity", "revenue-billing", "forecasting",
            "explore", "overview", "portfolio", "pricing");
  }

  @Test
  void isValid_acceptsPortfolio() {
    assertThat(validRequestForPage("portfolio").isValid()).isTrue();
  }

  @Test
  void isValid_acceptsPricing() {
    assertThat(validRequestForPage("pricing").isValid()).isTrue();
  }

  @Test
  void isValid_rejectsUnknownPage() {
    assertThat(validRequestForPage("domains").isValid()).isFalse();
  }

  @Test
  void isValid_rejectsNullPage() {
    AiAnalyzeRequest req = validRequestForPage("portfolio");
    req.page = null;
    assertThat(req.isValid()).isFalse();
  }
}
