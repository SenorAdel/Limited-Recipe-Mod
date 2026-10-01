# Limited Recipe Access

**Beta** — a standalone NeoForge mod for controlling who can craft recipes, what they can see in JEI, and how recipe definitions behave. This project does not modify or depend on SimpleCraftEditor or KubeJS.

## Compatibility

| Component | Supported version |
| --- | --- |
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.111 minimum (the declared loader dependency) |
| Java | 25 |
| JEI | Optional; required only for JEI hiding/integration |

This is a NeoForge mod; Forge and Fabric are not supported.

## Install and open the editor

1. Install the same mod JAR on the server and every connecting client.
2. Install JEI separately on clients if you want the JEI visibility controls to affect JEI.
3. Start the world/server and run `/recipeaccess` as an operator (gamemaster command permission).

Back up the world before testing a beta build on an established save.

## Features

- Independent craft and JEI policies per recipe: **Anyone**, **Whitelist**, or **Blacklist**.
- A global recipe-disable toggle, plus per-player exceptions.
- Player selection from online profiles and the server's `usercache.json`.
- A recipe-type filter and a browser for reviewing, editing, enabling/disabling, and deleting saved rules.
- A visual item-slot editor for shaped and shapeless crafting, smelting, blasting, smoking, campfire cooking, stonecutting, and smithing recipes. Unsupported or custom recipe formats can be edited as raw JSON when their serializer supports it.
- A searchable item catalog that includes items registered by installed mods.
- Raw recipe and access-rule JSON editors.
- Optional JEI integration. JEI visibility is applied separately on each connected client's JEI installation.

## Access behavior

Crafting and JEI visibility are independent policies. **Anyone** allows access to everyone. **Whitelist** allows only selected UUIDs; **Blacklist** denies the selected UUIDs and allows everyone else. An empty whitelist allows nobody; an empty blacklist blocks nobody.

When **Disable recipe for everyone** is enabled, crafting is denied except for players explicitly included in that recipe's craft whitelist. A JEI whitelist similarly lets selected players see a recipe/output hidden from everyone else. Turning a saved rule **off** suspends all of that rule's restrictions.

Player-specific crafting rules cannot reliably be enforced for passive campfire cooking or automated machines, because those outputs do not identify the player who owns them. Global disable covers the standard supported cooking recipe families. Machine-specific integrations are not included.

## Saved data

- Access policies: `<world>/data/limitedrecipe/permissions.json`
- Recipe JSON overrides: `<world>/datapacks/limitedrecipe_overrides/`

Recipe edits are saved as a world datapack and recipes are reloaded. **Restore original** removes the override for that recipe. Keep backups of these files if editing them manually.

## Build from source

Install a 64-bit Java 25 JDK, then run:

```powershell
.\gradlew.bat build
```

The JAR is created in `build/libs/`. The repository includes the Gradle wrapper; generated `.gradle/` and `build/` files are intentionally excluded from Git.

## Beta release notes

See [CHANGELOG.md](CHANGELOG.md). CurseForge's **Release Type** is selected separately during file upload: choose **Beta** and select Minecraft **26.1.2** with the NeoForge loader. The JAR's `-beta.1` version label does not set the CurseForge release type by itself. [CurseForge file-type guidance](https://support.curseforge.com/support/solutions/articles/9000197242)

## Bug reports

Include the mod version, Minecraft/NeoForge versions, whether the issue is on the client or server, steps to reproduce, and the relevant log/crash excerpt. Do not post private UUIDs or unredacted server logs publicly.

## License

This project uses the [Limited Recipe Access Custom Community License](LICENSE). It permits personal use and no-charge modified forks under the stated attribution and notice requirements; it does not permit selling or redistributing unmodified copies. Independent add-ons may be sold only under the license's conditions, and may not bundle or contain this Mod. For CurseForge, choose **Custom License** and provide the text from `LICENSE`; CurseForge's upload release type (Beta/Release) is configured separately.
