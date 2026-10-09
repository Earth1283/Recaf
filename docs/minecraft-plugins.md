# Working with Minecraft plugins

Recaf has first-class support for server plugins: Bukkit / Spigot / Paper, and BungeeCord. This page covers what it
recognizes, how to give the decompiler the server API a plugin was written against, and how renaming keeps the plugin
loadable.

- [What Recaf recognizes](#what-recaf-recognizes)
- [Attaching the server API](#attaching-the-server-api)
- [Renaming classes safely](#renaming-classes-safely)
- [Navigating a plugin](#navigating-a-plugin)
- [Recovering names in obfuscated plugins](#recovering-names-in-obfuscated-plugins)
- [Configuration](#configuration)
- [Limitations](#limitations)
- [For developers](#for-developers)

## What Recaf recognizes

### Manifests

A plugin jar describes itself with a YAML file at the root of the jar.

| File               | Platform                 |
|--------------------|--------------------------|
| `plugin.yml`       | Bukkit / Spigot / Paper  |
| `paper-plugin.yml` | Paper                    |
| `bungee.yml`       | BungeeCord and its forks |

When a workspace contains one of these, the workspace summary shows a **Minecraft plugin** section with the plugin's
name, version, API version, main class (click to open it), bootstrapper and loader classes, and its dependencies.
Embedded jars are checked too.

Manifests are read as untrusted input. The YAML is never turned into objects, global tags are rejected, and size,
nesting depth, and alias expansion are limited. A manifest that is malformed or exceeds a limit is ignored rather than
causing an error.

`api-version` is read exactly as written. An unquoted `api-version: 1.20` is the number `1.2` to a YAML loader, but
Recaf treats it as the text `1.20`, which is what the author meant.

### Entry points

Entry points are listed in the workspace summary and let you jump straight to where a plugin starts running.

| Kind                            | Found when                                                                                                      | Methods                                                          |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| Bukkit plugin initializer       | The class extends `org.bukkit.plugin.java.JavaPlugin`, **or** is the `main` class of `plugin.yml` / `paper-plugin.yml` | `onLoad`, `onEnable`, `onDisable`                                |
| BungeeCord plugin initializer   | The class extends `net.md_5.bungee.api.plugin.Plugin`, **or** is the `main` class of `bungee.yml`               | `onLoad`, `onEnable`, `onDisable` (`()V`)                        |
| Paper plugin bootstrapper / loader | The class implements Paper's `PluginBootstrap` / `PluginLoader`, **or** is named by `bootstrapper:` / `loader:` in `paper-plugin.yml` | `bootstrap`, `createPlugin`, `classloader`                       |
| Velocity plugin initializer     | `@Plugin` annotation or a `ProxyInitializeEvent` / `ProxyReloadEvent` receiver (unchanged)                      |                                                                  |

The two signals for Bukkit and BungeeCord are independent: either one is enough. That keeps discovery working when
the class hierarchy is incomplete, which is normal because the server API is not inside the plugin jar. If the manifest
names a main class that has none of the lifecycle methods, the class itself is listed.

Paper bootstrappers and loaders run *before* the main plugin class exists, so they are easy to miss when reading a
plugin from `onEnable`.

## Attaching the server API

A plugin jar does not contain the API it uses. Without it, every reference to `org.bukkit...` or
`net.kyori.adventure...` is an unresolved type. Attaching the API adds it to the workspace as a library, so Recaf can
resolve those types.

### Why this matters

- **Decompilation.** The decompiler can check which methods override the server's, restoring annotations such as
  `@Override`, and resolve types it would otherwise have to guess.
- **Renaming.** This is the more important effect. Automatic renaming leaves alone any method that overrides a method
  from a library, but it can only tell if it can see the library. Without the API, `onEnable` looks like any other
  method and would be renamed, which stops the server from calling it. With the API attached it is left alone and the
  plugin's own methods are still renamed.

### How to attach it

Open the plugin. In the workspace summary, under **Minecraft plugin**, press **Attach** next to the API that was
detected, for example *Attach Paper API for 1.21*. Progress and the result are shown below the button.

Nothing is downloaded unless you press the button.

### Which version is chosen

For Bukkit, Spigot, and Paper plugins the **Paper API** is used. It is a superset of the Bukkit and Spigot APIs, so it
covers plugins written for any of them.

The version is picked from the plugin's `api-version`:

1. The newest version in that family is used, so `1.21` gives the newest `1.21.x`. `1.21.0` and `1.21` are the same
   release.
2. Pre-releases (`-rc`, `-pre`) are never chosen.
3. Where several builds of a version exist, a `stable` build is preferred over `beta`, then `alpha`, then the newest
   build number. Both of Paper's version formats are understood: `1.21.4-R0.1-SNAPSHOT` and
   `26.1.2.build.74-stable`.
4. If the family is no longer published (for example `1.16`), the oldest published version newer than it is used.
   These APIs only grow, so it is the closest match.
5. If the plugin does not declare an `api-version`, the newest version is used.

For BungeeCord plugins, which declare no API version, the newest release of the BungeeCord API is used.

### Libraries

The API's own types refer to other libraries, such as Adventure and Guava. By default Recaf also attaches the libraries
the API depends on, so that those types resolve too. Only what is needed to read the API's types is included: compile
scope, non-optional dependencies, with the usual Maven rules for parent POMs, version management, BOM imports, and
exclusions. A library that cannot be handled is skipped and reported, and never stops the API itself being attached.

Large libraries are skipped by default. Paper depends on `fastutil`, which is over 20 MB, and loading it costs memory
and time for very little benefit. The result lists anything that was skipped and why.

### Safety and caching

- Downloads use HTTPS only. Plain HTTP is accepted only for the loopback address, to support a local mirror.
- Each jar is checked against the SHA-1 the repository publishes next to it, and rejected on a mismatch.
- Maven coordinates and file names taken from downloaded files are validated before they are used to build URLs or
  paths, so a hostile file cannot make Recaf read or write outside its cache.
- Files are cached in Recaf's directory under `cache/minecraft-plugin-api`. Each file is only downloaded once. The
  first attach of Paper's API takes around ten seconds, and later ones are quick.
- Attaching again does nothing for libraries that are already present.
- Attaching clears cached decompilation results for the plugin's classes, so what you see next is decompiled with the
  API available.

## Renaming classes safely

The server finds a plugin by looking up the class named in its manifest. If that class is renamed and the manifest is
not, the plugin fails to load. This matters most for obfuscated plugins, where renaming the main class is one of the
first things you do.

After any mapping operation, Recaf updates these top-level keys in the plugin's manifest to match the new class names:
`main`, `bootstrapper`, and `loader`.

- The edit is made in place in the text. Comments, ordering, quoting style, and line endings are preserved.
- Only top-level keys are changed. An indented `main:` belongs to something else and is left alone.
- Package renames are covered, because they are applied as renames of each class.
- Every update is logged. If a name needs updating but the manifest uses a layout Recaf cannot edit, such as flow-style
  YAML or a multi-line value, nothing is changed and a **warning is logged** naming the key, so that a stale name is
  never left behind unnoticed. Edit it by hand in that case.

## Navigating a plugin

An obfuscator can rename a plugin's own classes and members, but not the server API. The server calls `onEnable` by
name, reads `@EventHandler` annotations at runtime, and the plugin passes plain strings to the API for command names,
config paths, and permission nodes. Recaf reads the plugin's bytecode for those API uses and builds an outline of the
plugin from them, so you can find your way around even when every name is `a`, `b`, or `c`.

### What is found

| Kind                    | Found from                                                                                             |
|-------------------------|--------------------------------------------------------------------------------------------------------|
| Main class, lifecycle   | The manifest, or a class extending `JavaPlugin` / BungeeCord's `Plugin`. Lists `onEnable`, `onDisable`, `onLoad` |
| Commands                | `getCommand("name")`, then `setExecutor(...)` / `setTabCompleter(...)`. BungeeCord `registerCommand`, `CommandMap.register`, and `Command` subclasses passing their name to `super(...)`. Manifest commands without an executor are handled by the main class |
| Listeners               | `registerEvents(listener, plugin)`, classes implementing `Listener`, and classes with `@EventHandler` methods |
| Event handlers          | `@EventHandler` methods (Bukkit and BungeeCord) and Velocity `@Subscribe`, with priority and `ignoreCancelled` |
| Events fired, custom events | `callEvent(new SomeEvent(...))`, and event classes the plugin declares                             |
| Scheduled tasks         | `BukkitScheduler.runTask*` and `schedule*`, `BukkitRunnable.runTask*`, Folia schedulers, BungeeCord's `TaskScheduler`. Lambdas are linked to their method |
| Config paths            | `getString`, `getInt`, `set`, `contains` and the other `ConfigurationSection` methods. Paths read through `getConfigurationSection("a").getString("b")` are joined into `a.b` |
| Permissions             | `hasPermission`, `isPermissionSet`, `setPermission`, `new Permission(...)`, and the manifest's `permissions` |
| Other keys              | Plugin messaging channels, `NamespacedKey`s, metadata keys, and other plugins or Vault services looked up |
| Other types             | `ConfigurationSerializable` (with `@SerializableAs`), inventory holders, PlaceholderAPI expansions, plugin message listeners |

String arguments are followed back to where they come from, so a command name stored in a local variable or a
constant field is still found. Strings that are built at runtime, or decrypted by the obfuscator, are not. Run
Recaf's deobfuscation first if the plugin encrypts its strings.

Classes from libraries that plugins commonly bundle, such as bStats and HikariCP, are ignored. This can be turned off in
the configuration.

### The plugin navigator

Open it with **Analysis → Minecraft plugin navigator**, or the button in the workspace summary. It opens as a tab next
to the workspace explorer, and shows the plugin's structure grouped into sections: plugin entry points, commands,
events, listeners, tasks, config paths, permissions, and the other keys and types listed above.

- Type in the search field to filter. Every word must match, and matching covers the kind of entry, its key, and where
  it is, so `permission admin`, `PlayerJoin`, or `max-homes` all work.
- Double-click an entry, or press Enter, to open the code. Right-click it for the usual class or method menu.
- Entries such as *Registered in* and *Scheduled in* jump to the other end: where a listener is registered, or where a
  task is scheduled.
- Commands and permissions that the manifest declares but the code never uses are listed as such.
- The outline refreshes by itself after classes change, for example after renaming.

### Quick navigation

Press **Ctrl+Shift+G** to open quick navigation on its **Plugin** tab, and jump straight to an event handler, command,
permission, config path, or task by typing part of it. Plugin entries also appear in the **All** tab. The shortcut can
be changed in the keybinding settings.

### In the editor

Right-click a class, method, or field, including in decompiled code, for a **Minecraft plugin** submenu:

| On                                      | Shows                                                                           |
|-----------------------------------------|---------------------------------------------------------------------------------|
| An event class                          | All handlers of the event, and where it is fired                                |
| An event handler                        | The event class, other handlers of the same event, and where the event is fired |
| A listener, executor, completer or task | Where it is registered or scheduled                                             |
| A method that registers or schedules    | What it registers or schedules                                                  |
| A method using a config path, permission or other key | Every other place the same key is used                            |
| A field holding a key or a config value | Every place the key is used                                                     |

Every submenu ends with **Show in plugin navigator**, which opens the navigator filtered to that item.

## Recovering names in obfuscated plugins

**Mappings → Recover Minecraft plugin names** suggests meaningful names for the plugin's classes, fields, methods,
and variables, based on what the navigator finds. Nothing is renamed until you choose to apply.

### What names are suggested

| Target    | Example                     | Based on                                                                    |
|-----------|-----------------------------|-----------------------------------------------------------------------------|
| Class     | `HomesPlugin`               | The main class, named after the plugin in the manifest. Bootstrappers and loaders likewise |
| Class     | `HomeCommand`               | The executor of `/home`. Tab completers become `...TabCompleter`             |
| Class     | `PlayerListener`, `PlayerJoinListener` | The events a listener handles: one event, or the common category of several |
| Class     | `RepeatingTask`             | How the task is scheduled (`runTaskTimer`, `runTaskLater`, async)           |
| Class     | `CustomPlayerEvent`, `HomeData`, `ShopExpansion` | Custom events, `@SerializableAs("...")`, PlaceholderAPI identifiers |
| Field     | `maxHomes`                  | Assigned from `config.getInt("settings.max-homes")`                          |
| Field     | `homeCountKey`              | Assigned `new NamespacedKey(plugin, "home_count")`                           |
| Field     | `PERMISSION_ADMIN`          | A constant passed to `hasPermission`. Config path, channel and other key constants likewise |
| Field     | `plugin`, `config`, `playerListener`, `players` | The field's type: API types, renamed plugin classes, and collections of them |
| Field     | `instance`                  | A static field holding its own class                                        |
| Method    | `onPlayerJoin`              | The event an `@EventHandler` receives                                       |
| Method    | `getMaxHomes`, `setPrefix`, `isEnabled`, `getInstance` | Methods that only get or set a field, named after the field |
| Method    | `getPrefix`                 | Returns `config.getString("messages.prefix")`                                |
| Method    | `registerListeners`, `registerCommands`, `startTasks`, `loadConfig`, `setupEconomy` | What the method does with the API |
| Variable  | `event`, `sender`, `args`   | Event handler and command method parameters, and the variable's type or config path |

Variables can only be renamed when the plugin still has variable debug information.

Methods the server calls by name, such as `onEnable` or `onCommand`, are never renamed. When the API is attached,
neither is any other method that overrides the API. Methods are renamed across their whole class hierarchy, so
overrides keep matching.

### Choosing what to apply

Each suggestion shows the current name, the new name, the kind of clue it is based on, a confidence from 0 to 100, and
the reason in words, such as *Value of config path 'settings.max-homes' (getInt)*.

- Suggestions for names that look obfuscated, with a confidence of at least 50, are selected to begin with.
- **Only obfuscated names** hides suggestions for names that already look like a person wrote them.
- Filter by kind of target and by clue, and search across names and reasons.
- Edit a new name by double-clicking it. Editing a name selects it.
- Double-click any other part of a row to open the code it is about.
- **Apply selected** applies the selection through Recaf's normal mapping process, so the manifest is updated and the
  mappings can be exported like any others. A suggestion that would clash with an existing name, or is not a valid
  name, is skipped and logged. Afterwards the list shows what is left to suggest.

Names are chosen so they do not collide with each other or with existing names: a second `HomeCommand` in the same
package becomes `HomeCommand2`.

## Configuration

Settings for the API attachment are under the analysis services as **Minecraft plugin API**.

| Key                       | Default | Meaning                                                              |
|---------------------------|---------|----------------------------------------------------------------------|
| `include-dependencies`    | `true`  | Also attach the libraries the API depends on                         |
| `max-dependencies`        | `80`    | Most libraries to attach alongside the API                           |
| `max-dependency-size-mb`  | `8`     | Libraries larger than this are skipped                               |
| `max-api-size-mb`         | `64`    | Largest API jar that will be accepted                                |

Settings for the navigator and name recovery are under the analysis services as **Minecraft plugin semantics**.

| Key                       | Default | Meaning                                                              |
|---------------------------|---------|----------------------------------------------------------------------|
| `skip-shaded-libraries`   | `true`  | Ignore classes of commonly bundled libraries, such as bStats         |

The quick navigation shortcut is `quicknav-plugin` in the keybinding settings.

## Limitations

- Velocity plugins are recognized as entry points, but do not have a manifest-driven API attach. Velocity plugins are
  described by an annotation, so there is no `api-version` to read.
- Spigot's own API and older Paper APIs that are no longer published are not separate choices. The Paper API is used
  for all Bukkit-family plugins.
- Plugins that use server internals (NMS) refer to code that is not in any API jar. Attaching the API does not make
  those types resolve, and the right mappings for them are separate.
- Manifest updates after renaming cover `main`, `bootstrapper`, and `loader`. Class names that appear elsewhere in a
  manifest are not updated.
- The navigator and name recovery read string arguments that are constants. Strings built at runtime or decrypted by
  the obfuscator are not seen, so run string deobfuscation first.
- Code that wraps the API in the plugin's own helpers, such as a custom config wrapper or command framework, is only
  partly understood. The API calls inside the helper are found, but not what each caller passes to it.
- Variables are only renamed when the plugin still has variable debug information. Most obfuscators remove it, in
  which case the decompiler's own variable names are kept.
- Suggested names are a starting point. Generic names such as `CommandHandler`, `EventListener`, or `MenuHolder` have
  low confidence and are not selected unless you choose them.

## For developers

The code lives in `software.coley.recaf.services.analysis.plugin` (manifests and rename safety) and
`software.coley.recaf.services.analysis.plugin.api` (attaching the API). Entry point discovery is in
`software.coley.recaf.services.analysis.entry`, and the UI is `MinecraftPluginSummarizer`.

| Class                                 | Role                                                                                         |
|---------------------------------------|----------------------------------------------------------------------------------------------|
| `MinecraftPluginManifestParser`       | Parses a manifest into a `MinecraftPluginManifest`                                           |
| `MinecraftPluginAnalysisService`      | Finds manifests in a workspace resource, or recursively in its embedded resources            |
| `MinecraftPluginManifestRewriter`     | Pure text function that applies class renames to a manifest                                  |
| `MinecraftPluginMappingListener`      | Runs the rewriter after mappings are applied and updates the workspace                       |
| `MinecraftPluginApiService`           | `detectRequests` finds what to attach. `attach` downloads and adds it. Blocks, so call it off the UI thread |
| `PaperApiVersionSelector`             | Pure version selection logic                                                                 |
| `MavenRepositoryClient`               | Reads versions and files from Maven repositories, with caching and checksum checks           |
| `MavenDependencyResolver`             | Small best-effort dependency resolver                                                        |
| `PluginApiFetcher`                    | The only point that touches the network. Replace it to test without one                      |

The navigator and name recovery live in `software.coley.recaf.services.analysis.plugin.semantic`. The UI is in
`software.coley.recaf.ui.pane.plugin`.

| Class                                 | Role                                                                                         |
|---------------------------------------|----------------------------------------------------------------------------------------------|
| `PluginSemanticService`               | Entry point. Caches the index of the current workspace, suggests names, and turns chosen suggestions into mappings |
| `PluginIndexer`                       | Reads bytecode into a `PluginIndex`. Add new API patterns in `handleCall`                    |
| `MethodFlow`                          | Follows a value back to the instruction that created it, through locals, `DUP`, casts and `Objects.requireNonNull` |
| `PluginIndex`, `PluginElement`        | Searchable result. Each element has a kind, a key, a location, and an optional related location |
| `NamingFacts`                         | Facts only used for naming: values flowing into fields, constant keys, accessors, method behaviors |
| `SemanticNameSuggester`               | Picks names from the index. Classes first, then fields, methods, and variables, so each can use the names before it |
| `NameHeuristics`                      | Decides whether a name looks obfuscated, and converts text to Java names                     |
| `PluginNavigation` (UI)               | Resolves index locations to paths, opens the navigator and the name recovery window          |
| `PluginContextMenuAdapter` (UI)       | Adds the plugin submenu to class, method and field context menus                             |

`PluginSemanticServiceTest` compiles a small stand-in for the Bukkit API and an obfuscated plugin with javac
(`ObfuscatedPluginFixture`), then checks what is indexed, what names are suggested, and that applying them leaves a
working plugin.

To support another entry point, implement `EntryPointDiscovery` as a CDI bean; it is picked up automatically.
To support another API, add a constant to `PluginApiTarget` with its repositories and a version selector, and map a
platform to it in `PluginApiTarget.forPlatform`.

Tests in `recaf-core` under the same packages show typical use. `MinecraftPluginApiService` is tested against an
in-memory repository (`FakeMavenRepository`), and `MinecraftPluginApiMappingTest` demonstrates the renaming effect
described above.
