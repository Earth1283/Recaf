# MCP server

Recaf includes a [Model Context Protocol](https://modelcontextprotocol.io) server, so coding agents such as Claude Code
can open an application, read and search it, decompile and edit it, and run deobfuscation, using the same services the
UI uses. Everything operates on Recaf's current workspace.

## Running it

**Let the agent launch Recaf (stdio, no UI).** Add this to the agent's MCP configuration:

```json
{
  "mcpServers": {
    "recaf": {
      "command": "java",
      "args": ["-jar", "/path/to/recaf.jar", "-h", "--mcp"]
    }
  }
}
```

For Claude Code: `claude mcp add recaf -- java -jar /path/to/recaf.jar -h --mcp`

With `-h` the process exits when the client disconnects. Without `-h` the UI opens too and stays open. Add `-i app.jar` to preload a workspace, or have the agent call
`workspace_open`.

**Attach the agent to a running Recaf, UI included (streamable HTTP).** Start Recaf normally with `--mcp-http`:

```
java -jar recaf.jar --mcp-http --mcp-token <secret>
```

Point the agent at `http://127.0.0.1:7733/mcp` with the header `Authorization: Bearer <secret>`. Pass a port as
`--mcp-http=7744`, or `0` for any free port. Without `--mcp-token` a random token is generated and written to the log.
When the UI is running the agent sees, and edits, the workspace you have open, and the UI updates as it works.

## Managing it from the UI

**Config > MCP server** opens a window with three tabs:

- **Connection**: whether HTTP and stdio are active and on which address, a checkbox to start or stop the HTTP server,
  the port and token, the two permission checkboxes below, and buttons that copy a ready-made setup for Claude Code or any
  client that reads an `mcpServers` JSON file.
- **Tools**: every tool, its tier, and whether it is currently exposed.
- **Activity**: a live log of the calls clients make (tool, result, how long it took, arguments), newest first.

Permission changes apply immediately, including to clients that are already connected: a tool that is turned off
disappears from the tool list and can no longer be called. The settings are saved in the config under `mcp-server`, so
they can also be edited from **Config > Settings**. Options passed on the command line take precedence, and the window
says so when it is holding a checkbox in place.

## Permissions

Tools are grouped into three tiers. A tool in a disabled tier is not listed and cannot be called.

| Tier      | Default | What it covers                                                            | Switch                               |
|-----------|---------|---------------------------------------------------------------------------|--------------------------------------|
| `READ`    | on      | Inspecting, searching, decompiling, analysis, computing                   | always on                            |
| `WRITE`   | on      | Editing classes and files, renaming, deobfuscating, exporting             | `--mcp-read-only`, or the window     |
| `EXECUTE` | **off** | Running scripts (arbitrary Java), attaching to other JVM processes        | `--mcp-allow-execute`, or the window |

`script_run` is arbitrary code execution inside the Recaf process, and `attach_open` loads an agent into another process.
Only turn on `EXECUTE` for agents you would let run code on your machine.

The HTTP transport only binds to loopback, requires the bearer token, and rejects requests whose `Host` or `Origin`
is not loopback so a web page cannot reach it through DNS rebinding. Treat the token like a password. The server speaks
only to the local machine; it does not offer remote access.

## Tools

Class names use internal form (`com/example/Foo`). Results that can be large are paged with `offset` and `limit`.

| Area       | Tools |
|------------|-------|
| Workspace  | `workspace_open` `workspace_add_library` `workspace_info` `workspace_close` `workspace_export` |
| Classes    | `class_list` `class_info` `class_decompile` `decompiler_list` `class_disassemble` `class_bytes` `class_diff` |
| Editing    | `class_assemble` `class_put_bytes` `class_delete` `java_compile` `file_write` `file_delete` `comment_set` |
| Files      | `file_list` `file_read` `comment_get` |
| Search     | `search_strings` `search_numbers` `search_references` `search_declarations` `search_instructions` `similar_classes` `similar_methods` |
| Graphs     | `inheritance_info` `inheritance_is_assignable` `callgraph_callers` `callgraph_callees` |
| Renaming   | `rename_class` `rename_member` `mapping_apply` `mapping_export` `mapping_formats` `mapping_generate` `mapping_similarity` |
| Deobfuscation | `transform_list` `transform_presets` `transform_run` |
| Analysis   | `analysis_anti_reversal` `analysis_entry_points` `analysis_areas` `analysis_hashes` `analysis_jar_signing` `analysis_android_permissions` `analysis_bytecode_hotspots` |
| Minecraft plugins | `plugin_manifest` `plugin_security_scan` `plugin_name_suggestions` `plugin_apply_names` |
| Decompiled export | `decompile_export` writes every class as `.java` under a directory so ordinary tools can grep it |
| Data       | `crypto_inspect` `crypto_auto_decode` `crypto_codec` `crypto_hash` |
| Other      | `phantom_generate` `recaf_info` `recaf_plugins` |
| `EXECUTE`  | `script_run` `attach_list` `attach_open` |

Editing round trip: `class_disassemble` returns JASM assembly, edit it, send it back with `class_assemble`. Errors are
returned as diagnostics and nothing is changed. `java_compile` does the same for Java source, and unresolved references
are stubbed automatically.

## Adding tools from a plugin

Tools come from `McpToolProvider` beans, so a Recaf plugin can add its own. Provide an application scoped implementation:

```java
@ApplicationScoped
public class MyTools implements McpToolProvider {
	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("my_tool", "What it does, for the model.", McpAccess.READ,
				McpToolSchema.create().requiredString("name", "What it is."),
				args -> McpToolResult.text("Hello " + args.requireString("name")));
	}
}
```

Throw `McpToolException` for failures whose message should reach the model. Any other exception is logged and reported
as an internal error.
