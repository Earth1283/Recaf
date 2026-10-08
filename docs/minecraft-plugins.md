# Working with Minecraft plugins

Recaf has first-class support for server plugins: Bukkit / Spigot / Paper, and BungeeCord. This page covers what it
recognizes, how to give the decompiler the server API a plugin was written against, and how renaming keeps the plugin
loadable.

- [What Recaf recognizes](#what-recaf-recognizes)
- [Attaching the server API](#attaching-the-server-api)
- [Renaming classes safely](#renaming-classes-safely)
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

## Configuration

Settings for the API attachment are under the analysis services as **Minecraft plugin API**.

| Key                       | Default | Meaning                                                              |
|---------------------------|---------|----------------------------------------------------------------------|
| `include-dependencies`    | `true`  | Also attach the libraries the API depends on                         |
| `max-dependencies`        | `80`    | Most libraries to attach alongside the API                           |
| `max-dependency-size-mb`  | `8`     | Libraries larger than this are skipped                               |
| `max-api-size-mb`         | `64`    | Largest API jar that will be accepted                                |

## Limitations

- Velocity plugins are recognized as entry points, but do not have a manifest-driven API attach. Velocity plugins are
  described by an annotation, so there is no `api-version` to read.
- Spigot's own API and older Paper APIs that are no longer published are not separate choices. The Paper API is used
  for all Bukkit-family plugins.
- Plugins that use server internals (NMS) refer to code that is not in any API jar. Attaching the API does not make
  those types resolve, and the right mappings for them are separate.
- Manifest updates after renaming cover `main`, `bootstrapper`, and `loader`. Class names that appear elsewhere in a
  manifest are not updated.

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

To support another entry point, implement `EntryPointDiscovery` as a CDI bean; it is picked up automatically.
To support another API, add a constant to `PluginApiTarget` with its repositories and a version selector, and map a
platform to it in `PluginApiTarget.forPlatform`.

Tests in `recaf-core` under the same packages show typical use. `MinecraftPluginApiService` is tested against an
in-memory repository (`FakeMavenRepository`), and `MinecraftPluginApiMappingTest` demonstrates the renaming effect
described above.
