# Plano: outros óculos no iOS e refactor Android + iOS

Primeira análise: 05/10/2026 (branch `ios-debug-free`). Atualizado no mesmo dia, depois dos commits `98553318` (óculos no iOS) a `e1b2f583` (refactor do iOS).

Objetivo: levar ao iOS os óculos que só funcionavam no Android e tirar o código repetido entre os dois apps, deixando no código compartilhado (`shared/commonMain`) tudo o que não depende da plataforma.

## 1. Situação

| | Antes (05/10) | Agora |
|---|---|---|
| Óculos no iOS | HeyCyan, Eyevue | + TuneBuds, MoYoung, Meta Ray-Ban, Mentra Live; Meizu com aviso (seção 2) |
| `MainViewController.kt` (iOS) | 2.197 linhas, tudo junto | 223 linhas (só a entrada Compose) |
| Controlador do app iOS | dentro do arquivo acima | `IosAppController.kt`: 705 linhas, só ações do app |
| Fluxos do assistente | uma cópia no Android e outra no iOS | iOS usa `GlassesAssistant` (comum); o Android usa `SpokenQuestionFlow` e as políticas comuns, com o resto em `ai/questions` (A3) |
| Clientes do relay | JSON à mão no iOS | `RelayAiServices.kt` (comum, `kotlinx.serialization`) |
| Protocolo TuneBuds | só no app Android | `shared/devices/tunebuds` (Android e iOS usam) |
| Testes do `shared` (`:shared:portabilityTest`) | 96 | 117, todos passando |
| `MainActivity.kt` (Android) | 12.110 linhas, 104 desvios `isXSelected()` | igual: ver seção 4 |

Nada disso foi testado com os óculos ainda. A compilação do app Android também não foi feita aqui, porque este Mac não tem o Android SDK (tarefa A0).

## 2. Óculos no iOS (feito)

| Óculos | Como funciona no iOS | O que falta |
|---|---|---|
| TuneBuds | O protocolo AB Mate passou para o código comum, com uma `TuneBudsSession` que não depende do link. No iOS ela roda pelo BLE `FDB3` (`FF17`/`FF18`), o mesmo que o app AB Mate usa no iOS; o Android continua no RFCOMM. Foto, vídeo, áudio, bateria, armazenamento, contagem, hora, versão e foto de IA. | Testar com os óculos. A sincronização de mídia não dá no iOS: os óculos entram no hotspot do telefone, e um app iOS não cria hotspot. |
| MoYoung / W620 | `CRPSmartGlasses.framework`, do mesmo pacote de onde vem o `.aar` do Android. Baixar com `ios/scripts/fetch_moyoung_sdk.sh`; só existe para iPhone. Bateria, contagem, hora, versão e foto de IA. | Testar. A sincronização por Wi-Fi não foi feita (o Android também não a mostra na tela). |
| Meta Ray-Ban | `meta-wearables-dat-ios` 1.0.0. Registro pelo Meta AI (modo desenvolvedor, retorno por `cyanbridge://`); a câmera responde "Testar imagem" e "Testar voz". O deployment target subiu de 16.4 para 17.2. | Testar; para publicar, pegar o app ID e o client token no Wearables Developer Center. |
| Mentra Live | `mentra-bluetooth-sdk-ios` 3.2.0-dev.227 (mesma versão do Android). O botão faz uma pergunta com foto; a foto vem por BLE para um receptor local em 127.0.0.1. | Testar. |
| Meizu MYVU | Não dá: depois do BLE, o relay do app é só RFCOMM. O cartão explica isso, e a pergunta por voz funciona pelo áudio Bluetooth dos óculos. | — |
| MemoMind | Ainda sem link no iOS: o perfil existe e a pergunta por voz usa o áudio Bluetooth dos óculos. O protocolo oficial publicado pela MemoMind já está no código comum (`shared/devices/memomind/`: GM, comandos, gravação Opus e a `MemoMindSession`, que não depende do link), e o Android está completo (SPP `0x7033`/`0x2024`, HUD, bateria, notificações, microfone Opus, botão → pergunta por voz pelo plugin Web Bridge). A instalação de `.gmp` não é pública. Resumo em `android/CyanBridge/docs/memomind/README.md`. | Etapa 2: link iOS (BLE GATT `0x2021`/`0x2022`, ou iAP2 com MFi) e hospedar plugins Web (`.mmpkg`); testar no hardware. |

## 3. Refactor do iOS (feito)

1. `4f79c2b8`: `MainViewController.kt` foi dividido (entrada Compose, `IosAppController`, `IosWifiP2pManager`, `IosDeviceBind`). Os clientes do relay foram para o código comum, reescritos com `kotlinx.serialization` (o regex antigo cortava respostas com aspas escapadas).
2. `03343cca`: os fluxos do assistente foram para `shared/assistant/GlassesAssistant.kt`, com portas para a plataforma:
   - `AssistantVoice`: ouvir, falar e fala contínua;
   - `AssistantBrain`: modelo local, provedor próprio ou relay;
   - `AssistantOutput`: status e conversa "Glasses";
   - `AssistantGlassesHooks`: o que os óculos precisam em volta de uma resposta; no HeyCyan/QCSDK, os modos AI-speak e liberar o modo IA.

   Testes cobrem a ordem dos ganchos, o silêncio, as falhas e a troca de pergunta.
3. `e1b2f583`: a sessão HeyCyan (QCSDK e o caminho BLE sem SDK) saiu do controlador para `IosHeyCyanGlasses.kt`. O controlador só despacha a ação para os óculos selecionados (Eyevue → outros → HeyCyan) e cuida do que é do app.

O que ainda falta no iOS (entra junto com A4, para servir aos dois apps):
- [x] Levar o handler do Eyevue (`handleEyevue`) para uma classe própria, como as outras (`IosEyevueGlasses`).
- [x] Trocar o despacho por classe por uma interface `GlassesDevice` no `commonMain` (`handle(action)`, `canTakeAiPhoto`, `requestAiPhoto()`, `assistantHooks`, conectar/desconectar). `IosHeyCyanGlasses`, os aparelhos de `IosOtherGlasses` (TuneBuds, SDK, Meizu) e o `IosEyevueGlasses` passam a implementá-la.

## 4. Plano do Android (pronto para executar)

A ordem importa: cada tarefa deixa o app compilando e funcionando, e as tarefas de cima reduzem o risco das de baixo. Todas precisam de teste no telefone com os óculos (regra do `AGENTS.md`).

### A0. Compilar e testar o que já mudou (pequena, primeiro)

O protocolo TuneBuds saiu do app (`devices/tunebuds/TuneBudsProtocol.kt`) e foi para `shared/devices/tunebuds/`. Os imports do app foram atualizados, e o teste foi para o `commonTest`. O app Android não foi compilado depois disso.

- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest` numa máquina com Android SDK.
- [ ] Teste rápido com TuneBuds: conectar, foto, bateria (o protocolo é o mesmo; só o lugar do arquivo mudou).
- [ ] Colocar `:app:assembleDebug`, `:shared:portabilityTest` e o build iOS (`-PenableAppleTargets=true :shared:compileKotlinIosSimulatorArm64`) num CI ou num script único.

### A1. Remover cópias diretas entre `app` e `shared` (pequena)

| Arquivo no app | Diferença para o shared | Ação |
|---|---|---|
| `CaptureTimer` | nenhuma | apagar do app |
| `FakeSummarizationService` | 4 linhas | apagar do app |
| `BleIpBridge` | 11 linhas | juntar e apagar |
| `GlassesSessionCoordinator` | 16 linhas | juntar e apagar |
| `VaultCrypto` | 22 linhas | o shared é `expect`; usar o `actual` do Android |
| `WqRecordParser` | 57 linhas | juntar (o app tem mais casos) e apagar |
| `ScannedDevice` | 43 linhas | usar o do shared |
| `EyevueProtocol` | app 414 × shared 199 | levar ao shared o que só o app tem e apagar |

E dentro do app: juntar os dois `LocalAgentPrefs` (`agent/` e `localagent/`) e os dois `RetryPolicy`.

### A2. TuneBuds pela sessão comum (pequena/média)

`TuneBudsManager` (≈600 linhas) repete o que `shared/devices/tunebuds/TuneBudsSession.kt` já faz.

- [x] `TuneBudsSppClient` implementa `TuneBudsTransport` (`write`, `maxWriteLength = 260`) e passa os bytes lidos para `session.onBytesReceived`.
- [x] `TuneBudsManager` vira um adaptador: estado de conexão do SPP, `TuneBudsSession` para os comandos, mais o que é só do Android: Wi-Fi do gerenciador de arquivos (`startFileManager`), que pode ir para a sessão se o iOS um dia precisar.
- [x] Os campos de capacidade que só o Android lê (`deviceAbility`, `screenConfig`, volume…) vão para `TuneBudsSessionState`.
- Feito: o `TuneBudsManager` caiu de 634 para 254 linhas. O handshake do hotspot (`TuneBudsFileStartup`, `TuneBudsRtspStartup`) continua no app, sobre `session.frames` e `session.request`. O iOS não mudou (`initialize()` sem `readCapabilities`).
- [ ] Testar com os óculos: conectar, foto, vídeo, áudio, bateria, armazenamento, contagem, hora, versão, foto de IA, sincronização de mídia e prévia ao vivo.

### A3. Assistente comum no Android (grande, maior ganho)

Antes, o fluxo de perguntas estava no `MainActivity` (≈4.350–5.800): `startParallelAudioQuestionIfEligible`, `onImageReadyForQuestion`, `offerFollowUp`, `captureOptionalImageQuestionFromBluetoothMic`, `speakImageQuestionCue`, `startVoiceQuestionFromUi` e as capturas por óculos (`capture*ImageForQuestion`).

Feito (branch `cyan-refactor-assistant-extract`):
- [x] Comum (`shared/assistant`): o parser das tags de rota (`SpokenDecision.kt`) e a janela de conversa (`SpokenConversation`) saíram do app. `SpokenQuestionFlow` é o fluxo SMART inteiro (decisão, câmera, busca na web, telefone, Live, ignorar, continuação na mesma conversa e pergunta de seguimento) atrás de portas: `SpokenQuestionMicrophone`, `SpokenQuestionBrain`, `SpokenQuestionActions`, `SpokenQuestionSpeech`. As decisões puras ficam em `AssistantQuestionPolicy.kt`: que fluxo e que provedor respondem (`AssistantQuestionRouting`), o que dizer quando a foto falha (`VisionReplyPolicy`), como responder a partir da busca (`WebAnswerPolicy`), a seleção da memória (`AssistantMemorySelection`) e a ordem do prompt de sistema (`AssistantSystemPrompt`). O `GlassesAssistant` aceita um `SpokenQuestionFlow` para o `askByVoice`.
- [x] Android (`app/.../ai/questions`): `GlassesQuestions` (por processo; o `MainActivity` se liga como `GlassesQuestionHost` para permissões, diálogos e tela bloqueada), `AssistantSpeaker` (o TTS que era do `MainActivity`), `QuestionMicrophone`, `AssistantProviderQueries`, `ImageQuestionCapture`, `ImageQuestionFlow`, `VoiceQuestionFlow` e `AndroidSpokenQuestionPorts` (as portas do fluxo SMART: sessões LiteRT, relay Pro, WebView, Local Agent, Live). Os desvios continuam antes do fluxo: `GeminiLiveQuestions` e `ExternalAssistantHandoff` (Tasker, app do assistente). A foto de cada família é `AndroidGlassesDevice.questionPhoto` (Meta, Eyevue, TuneBuds, Mentra), sem cadeia de `isXSelected()`.
- [x] `MainActivity`: de 5.069 para 1.860 linhas (o `ArchitectureGuardrailSourceTest` segue).
- [x] Ganchos que esperavam esta região: pergunta com foto no Mentra Live, sem SCO quando o perfil usa o microfone do SDK (`DEVICE_SDK`), sinal de áudio do app em volta das respostas (`VendorPlaybackSignal`), respostas no HUD do MemoMind (`showsAssistantAnswers` no perfil) e perguntas pelo botão dos óculos sem a tela (`GlassesButtonQuestions`).
- Igual ao antes, de propósito: os fluxos de foto e de voz Classic continuam com callbacks (TTS `onDone`), sem passar pelo modelo de uma pergunta por vez do `GlassesAssistant`, porque isso mudaria a ordem de cancelamento e de seguimento. O microfone das perguntas agora usa o `AudioSessionCoordinator` (abaixo).
- iOS: o `GlassesAssistant` não liga o fluxo SMART ainda. Faltam as portas: gravar a pergunta em arquivo (hoje o iOS só tem a transcrição do `SFSpeechRecognizer`), um modelo que decida pela gravação (ou uma variante por texto), a busca na web (no Android é um WebView; no iOS não há implementação) e sessões de conversa (só LiteRT no Android). O iOS já ganha: as respostas no HUD do MemoMind e a `VisionReplyPolicy` nas respostas do relay com foto.
- [x] Áudio das perguntas na sessão comum (branch `cyan-refactor-question-audio`): o `QuestionMicrophone` abre uma sessão `VoiceCapture` antes do bipe e entra na rota Bluetooth comum por ela (`DEVICE_SDK` no Mentra); a rota só volta ao normal quando nenhuma outra sessão (reunião, Live) a usa. Outro prompt falado recusa a pergunta antes do bipe ("The microphone is busy; please ask again"). A fala é um `TtsSpeaker` só (`AssistantSpeaker`, também para as respostas em streaming), e cada resposta segura uma sessão `PLAYBACK` ao lado do sinal do Mentra. Removidos por redundância: `AndroidAssistantVoice`, `TtsSpeechStream` e o apelido `localagent.AudioSessionCoordinator`.
- Limite em segundo plano: sem tela visível, o Android recusa o tipo `microphone` ao serviço da pergunta (regra "while-in-use"; o botão do MemoMind chega pelo socket SPP do app, que não é exceção). O serviço agora entra em primeiro plano sem esse tipo em vez de travar o app, e a pergunta diz que o Android bloqueou o microfone. O Mentra (PCM do SDK) não depende disso; o microfone Opus do MemoMind por SPP também não, mas ainda não é usado para perguntas.
- [ ] Testar com os óculos: `docs/refactor/HARDWARE_TEST_CHECKLIST.md`, seções "A3" e "process audio sessions".

### A4. Contrato comum de óculos (média/grande)

Os 104 desvios `isXSelected()` do `MainActivity` (Eyevue 30, TuneBuds 22, Meta 17, MoYoung 13, Meizu 11, HeyCyan 11) viram chamadas a uma interface `GlassesDevice` no `commonMain` (a mesma da seção 3).

- [ ] Uma implementação por óculos sobre os gerenciadores existentes (`TuneBudsManager`, `MoyoungW620Manager`, `MetaRaybanManager`, `MentraLiveManager`, `MeizuMyvuManager`, Eyevue, HeyCyan/`glasses_sdk`).
- [ ] O que aparece na tela continua vindo de `GlassesManagerGating` (o iOS já usa).

### A5. Controlador da tela Óculos comum (grande)

Depois de A3 e A4, as 66 `GlassesDashboardAction` são tratadas por um controlador no `commonMain`, que chama o `GlassesDevice` e o `GlassesAssistant`. `MainActivity` e `IosAppController` ficam com permissões, serviços em primeiro plano e ciclo de vida. Meta: `MainActivity` abaixo de 1.000 linhas.

- [x] `GlassesDashboardController` (comum): política de sessão exclusiva, gating da família, capacidades e painel do `GlassesDevice` selecionado; o resto vai para um `GlassesDashboardHost`. No Android o host é o `DashboardController`; no iOS, o `IosDashboardHost`. O gating do iOS é `withIosFamilyGating` (o `withFamilyGating` mais as diferenças do iOS).
- [ ] Testar nos dois apps com os óculos (o iOS ainda precisa compilar num Mac: `-PenableAppleTargets=true :shared:compileKotlinIosSimulatorArm64`).

### A6. Clientes de IA (média)

`CliRelayRouter` (816 linhas) faz o mesmo `/chat` e `/image-query` que `RelayAiServices` (comum), com mais coisas: escolha de provedor, tradução de erros (`RelayErrorLocalizer`) e endpoints OpenAI-compatíveis. Levar essas partes para o comum e fazer o `CliRelayRouter` usar os clientes comuns para HTTP e JSON.

### A7. Preferências e telas (contínua)

- [x] 73 arquivos chamam `getSharedPreferences` direto: trocar por preferências tipadas sobre `PlatformPreferences`, começando pelas que o iOS também usa (assistente, palavra de ativação, qualidade de imagem). Feito: cada arquivo, chave, tipo e padrão está declarado uma vez em `shared/prefs` (`PrefsInventoryTest` fixa todos). Faltam `ImageQuestionPreferences`, `SettingsActivity`, `OnboardingFeatureActivity` e `WelcomeActivity`, que leem as mesmas chaves direto, e os repositórios JSON do iOS.
- [x] Uma Activity só (branch `cyan-refactor-single-activity`): as telas viraram destinos da pilha do `MainActivity` (`ui/nav`: `AppRoute`, `AppNavigator`, `NavScreen`), com o mesmo ciclo de vida, resultados e Back de antes. De 43 Activities ficaram 6: o `MainActivity` (o ícone é um alias chamado `.ui.WelcomeActivity`), os trampolins dos links `cyanbridge://` (Meta) e `fersaiyan://pro-sub`, o retorno do checkout web, o portão de permissões do Gemini Live (aberto por serviços) e os plugins Web do MemoMind (WebView que sobrevive à rotação). O `ArchitectureGuardrailSourceTest` fixa essa lista. As abas, menos Óculos, ainda desenham a própria barra (cores iguais às de antes). Testar com `docs/refactor/HARDWARE_TEST_CHECKLIST.md`, seção "Single Activity", e refazer a captura de paridade (`SCREEN_PARITY.md`).
- [ ] Os layouts XML que sobraram (ver `COMPOSE_MIGRATION_PLAN.md`).

## 5. Um repositório ou dois?

**Recomendação: continuar com um repositório só.** Este refactor mostra o motivo: cada peça que saiu do iOS (assistente, relay, TuneBuds) foi para o `shared` e já pode ser usada pelo Android sem publicar pacote. Com dois repositórios, o `shared` viraria um terceiro pacote com versão própria, e cada mudança dessas exigiria dois ou três PRs.

O que vale mudar é a organização das pastas, quando nenhuma branch grande estiver aberta:

```
shared/          ← hoje em android/CyanBridge/shared (o código iOS mora dentro de "android/")
androidApp/      ← hoje android/CyanBridge/app
iosApp/          ← hoje ios/CyanBridgeKMPHost
third_party/     ← SDKs de fabricantes (QCSDK, MoYoung, MYVU)
docs/
```

## 6. Ordem sugerida a partir daqui

1. Testar no iPhone os óculos novos (seção 2) e o HeyCyan depois do refactor (o mesmo código, mas em outro arquivo).
2. A0 e A1 no Android.
3. A2 e A3 (o assistente comum também tira a última grande diferença de comportamento entre os apps).
4. A4 e A5, junto com o que falta no iOS (seção 3).
5. A6, A7 e a reorganização das pastas.
