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

package google.registry.ai.tools;

import com.google.gson.JsonObject;
import google.registry.model.console.User;

/**
 * A tool Claude can invoke during an AI analysis conversation to fetch additional registry data.
 *
 * <p>Tools are registered in {@link AiToolRegistry} and surfaced to Anthropic's tool-use API. The
 * orchestrator dispatches {@code tool_use} blocks back to the matching {@link
 * AiTool#executeWithStatus} implementation.
 *
 * <p>Tools should prefer returning a typed {@link ToolResult} over throwing — {@link
 * AiToolException} is reserved for genuinely unexpected runtime errors (the orchestrator catches
 * them and maps to {@link ToolResult.Status#INTERNAL_ERROR}). User-visible failure modes (bad args,
 * permission denied, out-of-range, empty-for-range) belong on {@link ToolResult.Status}.
 */
public interface AiTool {

  /** Stable tool name (matches what Claude will use in {@code tool_use} blocks). */
  String name();

  /** Short human description (used by Anthropic's tool selection logic). */
  String description();

  /** JSON-Schema input shape, conforming to Anthropic's tool input_schema requirements. */
  JsonObject inputSchema();

  /**
   * Run the tool and return a typed {@link ToolResult}. Implementations are responsible for
   * permission checks against {@code user} and for capping/truncating large result sets.
   */
  ToolResult executeWithStatus(JsonObject args, User user) throws AiToolException;

  /** User-visible failure during tool execution (bad args, permission denied, etc.). */
  class AiToolException extends Exception {
    public AiToolException(String message) {
      super(message);
    }

    public AiToolException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
