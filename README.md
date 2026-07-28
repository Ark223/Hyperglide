> [!CAUTION]
> Download this add-on only from the official [GitHub Releases page][repo-latest]. Files shared through unofficial websites, launchers, or mirrors may be modified or unsafe.

<div align="center">

<img src="icon.png" alt="Hyper Gliding" width="144">

# Hyper Gliding

A Meteor add-on focused on utility modules compatible with 2b2t.

[![License][shield-repo-license]][repo-license]
[![Latest Release][shield-repo-latest]][repo-latest]
[![Downloads][shield-repo-releases]][repo-releases]
[![Stars][shield-repo-stargazers]][repo-stargazers]

</div>

## 📖 About

Add-on for [Meteor Client][meteor] targeting the 2b2t anarchy server, with practical utility modules for automation.

## 📦 Requirements

- **[Minecraft 1.21.4][minecraft]**
- **[Fabric Loader][fabric]**
- **[Meteor Client][meteor]**
- **[Baritone][baritone]**

## 🧩 Modules

- **Air Place** - Places blocks without requiring an adjacent surface (avoids double placement).
- **Bounce Fly** - Uses elytra bouncing for fast highway travel with optional obstacle passing.
- **Fast Portal** - Builds and ignites nether portals near the player using minimal resources.
- **FD3 Crafter** - Strictly crafts FD3 firework rockets from available inventory ingredients.
- **Mining Tweaks** - Enables packet mining with validation, retries and configurable behavior.
- **Scaffolding** - Places selected blocks underneath the player while moving for safer bridging.

## 📁 Project Structure

```text
HyperGliding/
├── gradle/                         # Gradle wrapper files
├── libs/                           # Local dependencies
├── src/
│   └── main/
│       ├── java/dev/arkieee/hypergliding/
│       │   ├── mixin/              # Mixin classes
│       │   ├── modules/            # Meteor modules
│       │   └── Hypergliding.java   # Main entry point
│       └── resources/
│           ├── assets/hypergliding/
│           │   └── icon.png
│           ├── fabric.mod.json
│           └── mixins.json
├── build.gradle
├── gradle.properties
├── settings.gradle
├── gradlew
├── gradlew.bat
├── icon.png
├── LICENSE
└── README.md
```

## 🛠️ Building from Source

### Prerequisites

- **[JDK 21 or newer][java]**
- **[Git][git]**

### Windows

```powershell
git clone https://github.com/Ark223/HyperGliding.git
cd HyperGliding
.\gradlew.bat clean build
```

### Linux and macOS

```bash
git clone https://github.com/Ark223/HyperGliding.git
cd HyperGliding
./gradlew clean build
```

The compiled add-on JAR will be located in:

```text
build/libs/
```

## 🐛 Issues and Suggestions

Found a bug or have an idea?

- [Report a bug][repo-issues]
- [Suggest a feature][repo-issues]
- [Open a pull request][repo-pulls]

## 📄 License

This add-on is licensed under the [GNU General Public License v3.0][repo-license].

[java]: https://www.azul.com/downloads/
[git]: https://git-scm.com/downloads/

[minecraft]: https://prismlauncher.org/download/
[fabric]: https://fabricmc.net/use/installer/
[meteor]: https://meteorclient.com/
[baritone]: https://maven.meteordev.org/#/snapshots/meteordevelopment/baritone

[shield-repo-license]: https://img.shields.io/github/license/Ark223/HyperGliding?style=flat&labelColor=082f49&color=38bdf8
[repo-license]: https://github.com/Ark223/HyperGliding/blob/main/LICENSE

[shield-repo-latest]: https://img.shields.io/github/v/release/Ark223/HyperGliding?display_name=release&label=latest&labelColor=082f49&color=38bdf8
[repo-latest]: https://github.com/Ark223/HyperGliding/releases/latest

[shield-repo-releases]: https://img.shields.io/github/downloads/Ark223/HyperGliding/total?labelColor=082f49&color=38bdf8
[repo-releases]: https://github.com/Ark223/HyperGliding/releases

[shield-repo-stargazers]: https://img.shields.io/github/stars/Ark223/HyperGliding?style=flat&labelColor=082f49&color=38bdf8
[repo-stargazers]: https://github.com/Ark223/HyperGliding/stargazers

[repo-issues]: https://github.com/Ark223/HyperGliding/issues
[repo-pulls]: https://github.com/Ark223/HyperGliding/pulls
