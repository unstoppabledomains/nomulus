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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import javax.annotation.Nullable;

/**
 * Typed envelope returned by {@link AiTool#executeWithStatus}.
 *
 * <p>Tools should prefer returning a {@code ToolResult} (with one of the {@link Status} factories)
 * over throwing. The orchestrator serializes this envelope into the Anthropic {@code tool_result}
 * content and sets {@code is_error} based on {@link #isError()}.
 *
 * <p>Statuses:
 *
 * <ul>
 *   <li>{@link Status#OK} — query succeeded with data; LLM should use {@code data}.
 *   <li>{@link Status#EMPTY_FOR_RANGE} — query was valid but returned no rows; the {@code
 *       diagnostic} explains why (e.g. data extent, active filters).
 *   <li>{@link Status#INVALID_ARGS} — caller-supplied args were missing or malformed.
 *   <li>{@link Status#OUT_OF_RANGE} — requested range is outside the data window.
 *   <li>{@link Status#PERMISSION_DENIED} — caller lacks access to the requested resource.
 *   <li>{@link Status#INTERNAL_ERROR} — unexpected runtime failure inside the tool.
 * </ul>
 */
public record ToolResult(
    Status status, @Nullable JsonElement data, @Nullable String diagnostic) {

  public enum Status {
    OK,
    EMPTY_FOR_RANGE,
    INVALID_ARGS,
    OUT_OF_RANGE,
    PERMISSION_DENIED,
    INTERNAL_ERROR
  }

  public static ToolResult ok(JsonElement data) {
    return new ToolResult(Status.OK, data, null);
  }

  public static ToolResult emptyForRange(JsonElement data, String diagnostic) {
    return new ToolResult(Status.EMPTY_FOR_RANGE, data, diagnostic);
  }

  public static ToolResult invalidArgs(String message) {
    return new ToolResult(Status.INVALID_ARGS, null, message);
  }

  public static ToolResult outOfRange(String diagnostic) {
    return new ToolResult(Status.OUT_OF_RANGE, null, diagnostic);
  }

  public static ToolResult permissionDenied(String message) {
    return new ToolResult(Status.PERMISSION_DENIED, null, message);
  }

  public static ToolResult internalError(String message) {
    return new ToolResult(Status.INTERNAL_ERROR, null, message);
  }

  /**
   * Returns {@code true} for statuses the LLM should treat as errors (i.e. set {@code is_error} on
   * the Anthropic tool_result block). {@link Status#OK} and {@link Status#EMPTY_FOR_RANGE} are
   * non-error: the LLM should read the diagnostic and surface it to the user, not retry.
   */
  public boolean isError() {
    return switch (status) {
      case INVALID_ARGS, OUT_OF_RANGE, PERMISSION_DENIED, INTERNAL_ERROR -> true;
      case OK, EMPTY_FOR_RANGE -> false;
    };
  }

  /** Renders this result as {@code {status, diagnostic?, data?}}, omitting null fields. */
  public JsonObject toJson() {
    JsonObject obj = new JsonObject();
    obj.add("status", new JsonPrimitive(status.name()));
    if (diagnostic != null) {
      obj.addProperty("diagnostic", diagnostic);
    }
    if (data != null) {
      obj.add("data", data);
    }
    return obj;
  }
}
