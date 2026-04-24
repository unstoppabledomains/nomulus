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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** Request payload for the AI analysis endpoint. */
public class AiAnalyzeRequest {

  public String page;
  public String promptType;
  public JsonObject metadata;
  public JsonElement chartData;
  public String model;
  public String systemPrompt;
  public List<ConversationMessage> conversationHistory;

  /** A single message in the conversation history. */
  public static class ConversationMessage {
    public String role;
    public String content;
  }

  public boolean isValid() {
    return page != null
        && promptType != null
        && chartData != null
        && (page.equals("domain-activity")
            || page.equals("revenue-billing")
            || page.equals("forecasting")
            || page.equals("explore")
            || page.equals("overview"));
  }
}
