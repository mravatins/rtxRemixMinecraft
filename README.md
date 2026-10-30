# rtxRemixMinecraft

Fabric mod that path traces Minecraft 26.3 using NVIDIA RTX Remix 1.6.1. Windows + RTX card only.

## Building

You need JDK 25.

```
git clone https://github.com/mravatins/rtxRemixMinecraft.git
cd rtxRemixMinecraft
./gradlew build
```

(`gradlew.bat build` on Windows cmd/PowerShell.) The jar ends up in `build/libs/`.

## Running

Grab the x64 release runtime from the [remix-1.6.1 release](https://github.com/NVIDIAGameWorks/rtx-remix/releases/tag/remix-1.6.1) and extract it into `rtx-remix-runtime/` in your game folder.

To play: drop the jar in `mods/` along with Fabric API, using Fabric Loader 0.20.1+.

To run from source: put the runtime in `run/rtx-remix-runtime/` and run

```
./gradlew runClient
```

F8 opens the mod settings, F9 opens the Remix menu.
