# Contributing

Thanks for helping test Limited Recipe Access. This is a beta targeting Minecraft 26.1.2 and NeoForge.

## Before opening an issue

- Confirm the Minecraft, NeoForge, and mod versions.
- Include whether the problem is client-side, server-side, or both.
- Give short reproduction steps and relevant logs; redact player UUIDs, IPs, and private server details.
- Recipe serializer compatibility varies. Include the recipe type and a sanitized example JSON when relevant.

## Build locally

Use a 64-bit Java 25 JDK and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat build
```

Before submitting a change, verify the build succeeds. There is not yet an automated in-game test suite, so mention any manual client/server checks you performed.

## License and contributions

The project is distributed under the [Limited Recipe Access Custom Community License](LICENSE). Read it before redistributing a fork or creating an add-on.

By submitting a contribution to the official project, you grant Senor the non-exclusive, worldwide, royalty-free permission described in section 5 of the license to use, modify, and distribute that contribution as part of the Mod. You retain ownership of your contribution.
