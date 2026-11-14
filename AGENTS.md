# Product checks

- Before a UI change, find current screenshots of relevant, widely used services and inspect the pixels. Record the source in the change notes.
- Borrow familiar interaction patterns, not isolated decoration. Check the whole flow: daily use, setup, edit, save, cancel, errors, recovery.
- Before every output, remove avoidable words. Prefer recognizable icons and useful motion; keep short labels when an icon would be ambiguous.
- Do not add version names, internal concepts, or chat-only explanations to product screens.
- Daily protection must require no station selection or start action. Preserve saved stations and underground recovery behavior.
- Build against the real Android and Google SDK dependencies. Never ship classes compiled against invented API stubs.
- Verify save and service-stop paths before providing an APK; distinguish static checks from device tests.
