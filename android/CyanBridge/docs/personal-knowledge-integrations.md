# Personal knowledge integrations: Obsidian, ChatGPT, Claude

Status: Android implementation on `obsidian-chatgpt-claude-integrations`.

## Product goal

CyanBridge should be a private personal-AI memory layer, not a replacement for every assistant or note app the user already likes. External assistants and note apps are therefore **inbound knowledge sources**. CyanBridge can learn from material the user explicitly grants, while CyanBridge chats, private memory, images, and other local context are never synchronized back to ChatGPT or Claude by this integration.

## Current provider reality (August 2026)

Consumer ChatGPT and Claude account history is exposed through account data-export flows, not a documented OAuth-style API that lets another Android app continuously enumerate a user's consumer chat history. The initial integration therefore avoids undocumented session-cookie/token scraping.

Official references:

- OpenAI data export: https://help.openai.com/en/articles/7260999-how-do-i-export-my-chatgpt-history-and-data
- OpenAI export transfer notes (`conversations.json`): https://help.openai.com/en/articles/9106926-transfer-conversations-from-1-chatgpt-account-to-another-chatgpt-account
- Claude data export: https://support.claude.com/en/articles/9450526-export-your-claude-data
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files

## Architecture

### 1. Official exports for backfill

The user can import a ChatGPT or Claude export ZIP/JSON. Parsing is local. Conversations become provider-tagged FTS chunks. User-authored turns are retained separately in the parser model so future fact-candidate extraction never treats an assistant assertion as a user fact.

This is best for first-time onboarding and occasional full reconciliation, not daily sync.

### 2. Obsidian / Markdown vault as a two-way knowledge store

Android's Storage Access Framework lets the user grant CyanBridge access to one selected folder tree without broad storage permission. A vault is considered connected only after CyanBridge confirms that retained **read and write** permission exists.

The setup UI supports two cases:

- **Connect existing vault:** the user selects an existing Obsidian vault folder and Android grants scoped read/write access to that tree.
- **Create new vault:** the user enters a vault name, selects a parent location, and CyanBridge creates a normal Markdown vault folder there. CyanBridge stores the selected parent permission plus the created vault document ID as the logical root, so no all-files permission is needed.

CyanBridge can then:

- recursively index Markdown notes while skipping `.obsidian`/hidden metadata;
- create a `CyanBridge/` folder for notes managed from the app;
- create and update notes in that managed folder;
- list recent CyanBridge-managed notes and reopen them in the editor;
- preserve CyanBridge note creation metadata when editing;
- write Obsidian-compatible YAML tags;
- provide editor shortcuts for headings, bullet/numbered lists, task checkboxes, bold, italic, inline code, quotes, links, wiki links, and inline tags;
- re-index after a note is saved;
- periodically re-index the granted vault with WorkManager.

Other Markdown files in the vault remain readable/searchable but are not overwritten by the CyanBridge note editor. This makes the `CyanBridge/` folder a safe managed namespace while the rest of the vault remains under the user's normal Obsidian workflow.

#### Important encryption boundary

Obsidian compatibility requires the source files in the external vault to remain ordinary plaintext `.md` files. **CyanBridge Memory Vault encryption does not encrypt those external Markdown files.** The UI states this explicitly rather than implying that enabling app-vault encryption also protects Obsidian source files.

Users who need encryption at rest for the external vault should rely on device/storage encryption or an encrypted storage/sync provider that remains compatible with their Obsidian workflow. CyanBridge may keep a local searchable index of granted notes inside app storage, but that internal index and the external `.md` source files are separate storage layers.

### 3. Import Inbox as the automation boundary

The user may grant a second folder as an **Import Inbox**. CyanBridge periodically scans supported `.zip`, `.json`, `.md`, and `.txt` files there. This creates a provider-neutral automation boundary for tools outside Android.

A future CyanBridge desktop/browser companion can write normalized conversation snapshots into this folder (or a folder synchronized to it by Syncthing/FolderSync/etc.). That companion should:

1. run only after explicit user opt-in;
2. read conversations the user can already view in the browser;
3. never transmit browser cookies/session tokens to CyanBridge servers;
4. normalize messages locally into a simple source/conversation/role/timestamp/text schema;
5. write only changed conversations to the user's chosen sync location;
6. allow per-site and per-conversation exclusion.

This is a better long-term daily-sync mechanism than teaching the Android app to scrape private provider endpoints or store provider credentials.

### 4. Local RAG boundary

Imported chunks share CyanBridge's Room FTS/Memory Vault policy path, but prompt injection is deliberately stricter than ordinary indexing:

- imported material is searchable/indexed locally regardless of the current inference provider;
- imported material is appended to model prompt context only when `AiProviderType.LOCAL_MODELS` is selected;
- selecting CLI relay/company/cloud routing returns an empty imported-context block.

This enforces the product rule that imported personal knowledge does not silently flow back out to an external assistant.

### 5. Facts are derived, not copied blindly

The raw imported corpus and confirmed user facts are separate concepts. ChatGPT/Claude assistant answers can be wrong, speculative, or stale, so they should never directly become durable personal facts.

A follow-up fact-candidate pipeline should operate only on `KnowledgeDocument.userAuthoredText`, run with an on-device model, write to the existing candidate-review stores, and preserve source provenance. Historical imports should not automatically create "today" daily facts; candidates need original-message timestamps or an explicit review UI before promotion.

## Chat rendering

The shared chat surface uses a dependency-free KMP Markdown renderer for the patterns generated most often by LLMs: headings, lists, quotes, fenced/inline code, bold, italic, bold+italic, strike-through, links, inline math, and display math. Common LaTeX commands are converted to readable Unicode locally so math remains usable offline without a WebView or remote MathJax dependency.

The parser is intentionally conservative. Unknown LaTeX commands remain visible rather than being dropped. A richer native math layout engine can replace the normalizer later without changing chat-message storage.

## Next phases

- browser/desktop companion that incrementally writes changed ChatGPT/Claude conversations to the Import Inbox;
- explicit local-only "Extract fact candidates from imports" review flow;
- incremental file fingerprints so large Obsidian vaults only re-read changed files;
- search/browse the entire Obsidian vault from CyanBridge while continuing to restrict write-editing to the managed `CyanBridge/` namespace;
- iOS document-tree equivalent and shared integration UI;
- optional encrypted cross-device sync of normalized external knowledge, without attempting to encrypt the Obsidian source vault itself.
