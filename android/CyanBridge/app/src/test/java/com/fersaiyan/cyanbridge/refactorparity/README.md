# Refactor parity scaffolding

Delete after hardware validation of cyan-refactor.

These tests only prove that code moved or split on the cyan-refactor branch still behaves like the
pre-refactor code. Remove this whole directory in one commit once the hardware checklist
(docs/refactor/HARDWARE_TEST_CHECKLIST.md) passes.

- `RelayLegacyParityTest`, `RemoteOpenAiLegacyParityTest`: compare shared code with verbatim
  copies of the org.json code it replaced.
- `JsonCompatParityTest`: compares the shared JSON reader with Android's org.json.
- `GeminiLiveClientCharacterizationTest`: pins Live wire traffic through reflection into private
  members while `GeminiLiveClient` was split into components.
- `localagentservice/`: characterization of the `LocalAgentService` runtime split. Note that
  nothing starts `LocalAgentService` any more (`LocalAgentController` routes every command to
  `TaskerLocalAgentService`), so the production classes these cover are unreachable as well.
