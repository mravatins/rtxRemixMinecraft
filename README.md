# RTX Remix for Minecraft (Fabric, 26.3)

This Fabric mod path-traces Minecraft Java 26.3 with the **NVIDIA RTX Remix 1.6.1** runtime. It sends the world to Remix through the Remix C API (version 0.6, which ships inside the 1.6.1 runtime). Minecraft keeps running on its own Vulkan backend for everything else. It is written from scratch and is not a port of NVIDIA's mod.

## How it works

- **Vulkan backend**: The mod makes Minecraft try its Vulkan backend first.
- **Window handover**: On the first frame, the mod destroys Minecraft's Vulkan swapchain. It then starts Remix on the same window through `dxvk_CreateD3D9` and `RegisterD3D9Device`, so Remix (dxvk-remix, also Vulkan) owns presentation.
- **World**:
  - Chunk sections are captured when Minecraft compiles them and are uploaded as Remix meshes. There is one mesh each for the solid, cutout and translucent layers.
  - The block atlas is written out as a DDS file for Remix's materials.
  - LabPBR 1.3 resource packs (such as SPBR) are supported. Every channel of the `_n` and `_s` maps is used:
    - Normals become Remix normal maps.
    - Height becomes parallax occlusion mapping. Its depth is set under Integration (0 turns it off).
    - Ambient occlusion is baked into the albedo, because Remix has no AO input.
    - Smoothness becomes roughness.
    - F0 is mapped as follows. Reflectance from 0 to 229 becomes partial metalness. Hardcoded metals from 230 to 237 get their physical F0 color. Values from 238 to 255 become albedo-colored metal.
    - Subsurface scattering becomes Remix thin-opaque transmission, which you can turn off under Integration.
    - Emission becomes the emissive texture.
    - Porosity is ignored, because Remix renders no rain or wetness.
  - The camera, the sun and moon (distant lights), the sky (a dome light) and light-emitting blocks (sphere lights) are submitted every frame.
  - The dome light's texture is Minecraft's sky, baked on a background thread whenever it changes visibly. It has the sky color fading into the fog color at the horizon, the sunrise/sunset glow, the sun, the moon phase and the stars. Sunlight takes on the sunrise/sunset color while the sun is low.
  - Clouds follow Minecraft's cloud setting (fast, fancy or off) and are sent as lit geometry that casts shadows. The cloud distance is capped at 48 chunks. You can turn them off under Integration.
- **HUD and menus**: Minecraft's Vulkan pipeline still draws these. Each frame they are read back and drawn as a UI layer on Remix's D3D9 device, which composites them over the path-traced image.

## Install

1. Install Fabric Loader 0.20.1+ and Fabric API for Minecraft 26.3. Put this mod's jar in `mods/`.
2. Download `remix-1.6.1` from https://github.com/NVIDIAGameWorks/rtx-remix/releases/tag/remix-1.6.1. Extract the x64 runtime (`d3d9.dll`, `d3d9_x64.dll`, `.trex/` and so on) into `<game dir>/rtx-remix-runtime/`. You can choose a different folder in the mod's settings.
3. You need an RTX GPU with a current NVIDIA driver.

## Settings

- **Opening the settings**: Press **F8** (rebindable), or use the **RTX Remix...** button on *Options → Video Settings*.
- **Every Remix option in-game**: All 1,030 options from `RtxOptions.md` in the 1.6.1 runtime are available in-game.
  - They are grouped by category (`rtx.<category>.*`), and you can search them.
  - Booleans are toggles, ranged numbers are sliders, and everything else is a validated text field.
  - Hover over an option to see its description, default value and range.
- **How changes apply**: Changed options are saved to `config/rtxremix.json`. They are applied live through `remixapi_SetConfigVariable` and re-applied at every startup. Options that the runtime only reads at startup take effect after a restart.
- **Integration**: This page holds the mod's own settings: enable/disable, VSync, sun/sky/block-light intensity, LabPBR emission/parallax depth/ambient occlusion/subsurface scattering, the maximum number of block lights and the runtime folder.
- **Remix menu**: **F9** (rebindable) or the **Open Remix menu** button opens the runtime's own developer menu. While it is open, the game is paused and Minecraft ignores the mouse, so clicks only reach the Remix menu. Press Esc to close it. The runtime's Alt+X shortcut toggles the Remix menu on top of whatever Minecraft is showing, without changing Minecraft's screen.
- **Keyboard input**: On first start the mod adds `rtx.useNewGuiInputMethod = False` to `rtx.conf` in the game folder. Remix's newer input method takes keyboard input away from Minecraft's window. If you set the key yourself, the mod leaves it alone.
- **Lighting defaults**: Minecraft's own baked lighting is turned off for the meshes Remix gets: smooth-lighting ambient occlusion and the per-face directional shade. Remix computes both itself. The mod also applies a few Remix defaults unless you override them: `rtx.vertexColorStrength = 1.0`, and a less flattening local tonemapper (`rtx.localtonemap.shadows = 1.0`, `highlights = 2.0`, `boostLocalContrast = True`), and an auto-exposure floor (`rtx.autoExposure.evMinValue = 0.0`) so nights stay darker than days.
- **VSync**: Remix presents with the mod's own VSync setting (on by default, under Integration), not Minecraft's.

## Current limitations

- **What is path-traced**: Terrain, fluids, items in the world (dropped, in item frames, held by mobs) and particles. Other entities, block entities and weather are not sent to Remix yet. The held item is drawn by Minecraft into the HUD layer.
- **Animated textures**: Animated block textures play as Remix sprite sheets, on Remix's clock rather than Minecraft's, and without frame interpolation. Their LabPBR maps stay on the first frame. Animated item and particle textures use their first frame.
- **Particles**: Particles at full block light (flames, for example) are emissive; the rest are lit by the scene.
- **Water**: Remix has no notion of the camera being inside water. While the camera is underwater, the mod turns translucent surfaces into plain windows (no refraction) and uses Remix's volumetrics as water fog; your own values come back when you surface. Water uses Remix's animated water: a generated wave normal map, tiled in world space and scrolled by Remix (tune it with `rtx.translucentMaterial.animatedWater*`). It keeps no flow direction, and resource-pack water textures and normal maps are not used.
- **LabPBR**:
  - The parallax raymarch can step past a sprite's edge in the atlas and sample its neighbour at grazing angles.
  - Subsurface transmittance does not get the biome tint, so light through leaves is not tinted green.
  - OptiFine CTM in a pack is ignored.
- **Block lights**: Light-emitting blocks are approximated by sphere lights. Their colors come from heuristics on the block ID.
- **Off-screen geometry**: Only sections that Minecraft has compiled exist in the scene. Geometry behind the camera that Minecraft never compiled will not appear in reflections until you look at it.
- **Platform**: Windows only.

## Building from source

### Prerequisites

- **JDK 25** (for example [Eclipse Temurin 25](https://adoptium.net/temurin/releases/?version=25)). Set `JAVA_HOME` to it, or put it on your `PATH`.
- **Git**.
- Nothing else: the Gradle wrapper downloads Gradle 9.8, Fabric Loom, Minecraft 26.3 and Fabric API on the first build.

### Build the jar

```bash
git clone https://github.com/mravatins/rtxRemixMinecraft.git
cd rtxRemixMinecraft
./gradlew build
```

On Windows PowerShell or cmd, use `gradlew.bat build` instead. The first build takes a few minutes while Loom downloads and remaps Minecraft. The mod jar is written to `build/libs/rtx-remix-minecraft-<version>.jar`. Copy it into your game's `mods/` folder and follow [Install](#install).

### Run from the dev environment

```bash
./gradlew runClient
```

This starts Minecraft with the mod from source, using `run/` as the game folder. Extract the Remix runtime into `run/rtx-remix-runtime/` first (see [Install](#install), step 2). Without the runtime, the mod falls back to vanilla rendering and logs why.

To generate IDE project files, open the folder in IntelliJ IDEA (it imports the Gradle project) or run `./gradlew genSources` for decompiled Minecraft sources.

### Continuous integration

`.github/workflows/build.yml` builds the jar on every push and pull request and uploads it as a workflow artifact.
