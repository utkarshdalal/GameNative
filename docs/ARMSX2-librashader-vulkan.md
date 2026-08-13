# ARMSX2 × GameNative — Shaders RetroArch (librashader) no Vulkan Android

> **Propósito:** fonte de referência **exclusivamente Vulkan** para que OUTRO agente porte o padrão
> do core ARMSX2 para o compositor do GameNative (`VulkanRendererContext`). O documento é
> autossuficiente: não é preciso reinvestigar o código para executar o checklist da seção 6.
> Toda referência usa `arquivo:linha` do estado atual do repo.

**Decisão de escopo:** foco **só no caminho Vulkan**. O runtime OpenGL/GLES do librashader é
mencionado apenas como contexto. O público-alvo é quem vai mexer no renderer Vulkan do GameNative.

---

## 1. Contrato de uso (para o agente que vai portar)

- Leia as seções 3 (padrão de referência ARMSX2) e 4 (estado atual GameNative).
- Leia a seção 5 (comparativo) para saber onde o GameNative é inferior e **por quê**.
- Execute a seção 6 (checklist) — cada tarefa tem arquivo, função, mudança exata e critério de aceite.
- Rode a seção 7 (verificação) antes de declarar sucesso.
- **Não** troque topologia de imagens (atlas melonDS), não mude ABI, não mexa em `use_dynamic_rendering`,
  não reintroduza prebuilts em `jniLibs` e não reabra a saga da tela preta (ver seção 4.4).

---

## 2. Contexto dos dois sistemas (por que não há copy-paste)

### 2.1 ARMSX2 — emulador de PS2 (core PCSX2/AetherSX2)

- Tem um **pipeline de Graphics Synthesizer (GS)** com renderer dedicado Vulkan (`GSDeviceVK`).
- A chain RetroArch roda **no fim do `Merge` do frame** (post-processo, depois de ShadeBoost/FXAA),
  e o resultado é usado pelo presenter do próprio core. Há um ping-pong de targets interno
  (`m_current` ↔ `m_target_tmp`) e um tracker de layout por textura (`GSTextureVK`).
- `applyFrame` grava no **command buffer atual do emulador**, seguido de um submit dedicado.

### 2.2 GameNative — launcher de PC (Wine/Winlator) — app `app/`

- `VulkanRendererContext` é um **compositor de janelas X11/Wine** (desenha janelas de jogos de PC em
  um `offscreenImage` e apresenta na swapchain).
- A chain roda como pós-processo sobre o `offscreenImage`, com target próprio `filterOutputImage`.
- `applyFrame` + present ficam no **mesmo command buffer** (`filterCmdBuf`), 1 submit por frame.
- Não existe skip-duplicate-frame / FIFO throttle no caminho de present (a arquitetura do anel do
  ARMSX2 não se aplica diretamente — ver seção 5, "Submit/anel").

**Consequência:** o port é de **padrões**, não de código. Os padrões a carregar são: (a) params
aplicados só na render thread via geração atômica, (b) latência de falha + fallback para o frame sem
shader, (c) resync de layout explícito, (d) invariantes de submit.

---

## 3. Referência: implementação ARMSX2 (padrão a portar)

### 3.1 Build / empacotamento do `liblibrashader.so`

Arquivo: `ARMSX2/platforms/android/app/src/main/cpp/3rdparty/librashader/CMakeLists.txt`

- **Rust é opcional** (linhas 27-34): sem `cargo`, `ARMSX2_HAVE_LIBRASHADER=OFF` e o feature compila
  para fora via `#ifdef ARMSX2_HAS_LIBRASHADER`.
- **Pin fixo** (linha 24): `LIBRASHADER_PIN=87e8a97b50516d997defeaa168173dcd185d4022` — bump
  deliberado, nunca branch.
- **Fetch no configure-time** (94-99): `FetchContent` de `SnowflakePowered/librashader.git`, com
  `SOURCE_SUBDIR` inexistente só para popular a árvore sem `add_subdirectory`.
- **Build cargo** (108-132): `cargo build --release --no-default-features --features
  runtime-vulkan,runtime-opengl`, com `CARGO_TARGET_<TRIPLE>_LINKER` = clang do NDK, API 26.
- **Soname** (116-122): `RUSTFLAGS=-C link-arg=-Wl,-soname,liblibrashader_capi.so`. Sem isso o
  `DT_NEEDED` do `emucore.so` guardaria o path completo e o Android (que resolve por nome em
  `lib/<abi>/`) falharia ao carregar.
- **`libc++_shared.so` ao lado** (141-172): as deps C++ do librashader (glslang/spirv-cross) linkam o
  runtime compartilhado, então o `.so` precisa do `libc++_shared.so` junto; faltando, **todo** o
  dlopen chain do app morre com `UnsatisfiedLinkError` mesmo sem usar shaders.
- **Link no app** (`ARMSX2/platforms/android/app/src/main/cpp/CMakeLists.txt:72-77, 172-188`):
  `add_subdirectory(3rdparty/librashader)`; quando `ARMSX2_HAVE_LIBRASHADER`, define
  `ARMSX2_HAS_LIBRASHADER=1` no target `PCSX2_FLAGS` e linka o `librashader` (IMPORTED SHARED) ao emucore.

### 3.2 Integração Vulkan — `GSDeviceVK`

Arquivo: `ARMSX2/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp`
Header: `ARMSX2/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.h`

Include gateado (`.cpp:54-60`):
```cpp
#ifdef ARMSX2_HAS_LIBRASHADER
#define LIBRA_RUNTIME_VULKAN          // declara os entry points Vulkan no header do librashader
#include "librashader.h"
#endif
```

Estado da chain (`.h:567-571`):
```cpp
void* m_shader_chain = nullptr;        // libra_vk_filter_chain_t opaco p/ o header não incluir librashader.h
std::string m_shader_chain_preset;
bool m_shader_chain_failed = false;
// + m_shader_frame_count, m_shader_param_generation (u64)
```

**`DoApplyShaderChain`** (`.cpp:4721-4832`) — a peça central:

```cpp
bool GSDeviceVK::DoApplyShaderChain(GSTexture* sTex, GSTexture* dTex)
{
  // 1) LATCH de falha: preset quebrado NÃO é recompilado 60x/s (4728-4729)
  if (m_shader_chain_failed && m_shader_chain_preset == GSConfig.ShaderChainPreset)
    return false;

  // 2) Cria/recria a chain quando o preset mudou (4731-4769)
  if (!m_shader_chain || m_shader_chain_preset != GSConfig.ShaderChainPreset)
  {
    DestroyShaderChain();  // 4676-4690: libra_vk_filter_chain_free
    m_shader_chain_preset = GSConfig.ShaderChainPreset;
    libra_shader_preset_t preset = nullptr;
    if (libra_error_t err = libra_preset_create(path, &preset)) { Report(...); m_shader_chain_failed = true; return false; }

    libra_device_vk_t vk = {};
    vk.physical_device = m_physical_device;
    vk.instance        = m_instance;
    vk.device          = m_device;
    vk.queue           = m_graphics_queue;
    vk.entry           = vkGetInstanceProcAddr;   // carrega Vulkan pelo loader (cavalga ICD custom/Turnip)
    libra_vk_filter_chain_t chain = nullptr;
    if (libra_error_t err = libra_vk_filter_chain_create(&preset, vk, nullptr, &chain))
    { Report(...); m_shader_chain_failed = true; return false; }
    // create() INVALIDA o preset -> NUNCA liberar o preset depois (4753-4754)
    m_shader_chain = chain; m_shader_frame_count = 0; m_shader_param_generation = 0;
  }

  // 3) Params: aplica na render thread (ver 3.4), 1 load atômico no caminho rápido (4772)
  ApplyShaderChainParams();

  // 4) frame(): a chain grava os próprios render passes -> não pode rodar dentro de um nosso
  EndRenderPass();                                    // 4778
  src->TransitionToLayout(GSTextureVK::Layout::ShaderReadOnly);    // 4782 (contrato: source SRO)
  dst->TransitionToLayout(GSTextureVK::Layout::ColorAttachment);   // 4783 (contrato: target CAO)
  const libra_image_vk_t in  = {src->GetImage(), src->GetVkFormat(), srcW, srcH};
  const libra_image_vk_t out = {dst->GetImage(), dst->GetVkFormat(), dstW, dstH};
  const libra_viewport_t vp  = {0.f, 0.f, dstW, dstH};
  libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(m_shader_chain);
  if (libra_error_t err = libra_vk_filter_chain_frame(&chain, GetCurrentCommandBuffer(),
        m_shader_frame_count, in, out, &vp, nullptr, nullptr))      // 4794
  { Report("frame", err); m_shader_chain_failed = true; return false; }
  m_shader_frame_count++;

  // 5) Resync do target (4803-4809): a chain deixou o target em CAO "por trás" do tracker.
  dst->OverrideImageLayout(GSTextureVK::Layout::ColorAttachment);   // só mexe no tracker, sem barreira
  dst->TransitionToLayout(GSTextureVK::Layout::ShaderReadOnly);     // barreira real p/ o estado do presenter
  dst->SetState(GSTexture::State::Dirty);

  // 6) FIX DO ANEL (4811-4830): submit imediato por frame de chain.
  ExecuteCommandBuffer(false);
  return true;
}
```

**Por que o `ExecuteCommandBuffer(false)` é crítico (4811-4828):** o librashader recicla objetos
por-frame (`VkImageView`/`VkFramebuffer`/descriptor sets) num anel de `frames_in_flight` (default 3):
o frame N destrói o que o frame N−3 gravou. Isso só é seguro se todo `frame()` é seguido de submit.
O PCSX2 chama `Merge()` (onde roda a chain) **antes** de decidir se apresenta; com
`SkipDuplicateFrames` (default) ou throttle FIFO, até `MAX_SKIPPED_DUPLICATE_FRAMES=3` frames pulados
deixam o librashader destruir views ainda ligadas ao command buffer em gravação →
`VUID-vkDestroyImageView-imageView-01026` + **SIGSEGV no Adreno** no submit. O fix mantém
`NUM_COMMAND_BUFFERS=3` e o anel do librashader avançando em lockstep (1 submit por frame de chain).

### 3.3 Chamada no frame — `GSDevice` / `GSRenderer`

**`GSDevice::ApplyShaderChain`** (`ARMSX2/pcsx2/GS/Renderers/Common/GSDevice.cpp:1326-1352`):
```cpp
bool GSDevice::ApplyShaderChain(const GSVector2i& output_size)
{
  FlushDeferredDraws();
  if (!GSConfig.ShaderChainEnabled || GSConfig.ShaderChainPreset.empty() || !m_current)
    return false;                       // guarda também aqui (backends sem chain custam zero)
  if (output_size.x <= 0 || output_size.y <= 0) return false;
  GSTexture*& dTex = (m_current == m_target_tmp) ? m_merge : m_target_tmp;  // ping-pong
  if (!ResizeRenderTarget(&dTex, output_size.x, output_size.y, false, false)) return false;
  if (!DoApplyShaderChain(m_current, dTex))
    return false;                       // SÓ troca m_current em sucesso (preset quebrado = sem shader)
  m_current = dTex;
  return true;
}
```
**Invariante anti-tela-preta:** a chain lê `m_current`, então não pode escrever nela; o target usa o
ping-pong; em falha `m_current` permanece no frame sem shader.

**`GSRenderer::Merge`** (`ARMSX2/pcsx2/GS/Renderers/Common/GSRenderer.cpp:313-345`): a chain roda
**por último** no post-processo (depois de `ShadeBoost` e `FXAA`), no **rect aspect-correto
on-screen** (calculado por `CalculateDrawSrcRect`/`CalculateDrawDstRect`), não a janela. Motivo
documentado (316-331): shaders que geram detalhe por pixel (CRT/scanline) precisam rodar na
densidade de pixels da tela; e como o librashader mapeia a entrada inteira para o viewport inteiro,
um target com aspect errado estica a imagem.

### 3.4 Config e parâmetros — padrão "geração atômica"

**Config** (`ARMSX2/pcsx2/Config.h:1049-1053`):
```cpp
bool ShaderChainEnabled = false;
std::string ShaderChainPreset;
```
INI keys `EmuCore/GS/ShaderChainEnabled` e `ShaderChainPreset`
(`ARMSX2/pcsx2/Pcsx2Config.cpp:1188-1189`). **Não existe opção libretro** — é feature só do app Android.

**Parâmetros** (`ARMSX2/pcsx2/GS/Renderers/Common/GSDevice.cpp:29-66`):
```cpp
std::mutex s_shader_param_mutex;
std::string s_shader_param_preset;
std::vector<std::pair<std::string, float>> s_shader_params;
std::atomic<u64> s_shader_param_generation{0};

void GSDevice::SetShaderChainParams(std::string preset, std::vector<...> params) {
  { lock; s_shader_param_preset = move(preset); s_shader_params = move(params); }
  s_shader_param_generation.fetch_add(1, std::memory_order_release);   // bump SÓ depois do store
}
u64 GetShaderChainParamGeneration() { return load(acquire); }         // caminho rápido: 1 load atômico
bool GetShaderChainParams(const std::string& preset, ...) { lock; se preset != guardado -> false; ... }
```
Consumidor (`GSDeviceVK::ApplyShaderChainParams`, `.cpp:4692-4719`): se `generation ==
m_shader_param_generation` retorna (fast path); senão copia e chama `libra_vk_filter_chain_set_param`
por nome, ignorando nomes desconhecidos (preset pode ter sido trocado sob overrides velhos). Header
(`GSDevice.h:1638-1640`) é explícito: *"a chain é single-threaded, a UI NUNCA deve chamar
`libra_*_filter_chain_set_param`"*.

### 3.5 JNI + UI

**JNI** (`ARMSX2/platforms/android/app/src/main/cpp/native-lib.cpp`):
- `shaderPresetParams(path)` (4876-4952) → JSON `[{name,description,initial,minimum,maximum,step}]`
  via `libra_preset_create` + `libra_preset_get_runtime_params`. Parse de arquivo puro, seguro na UI
  thread (não toca em `VkDevice`/contexto). Retorna `nullptr` sem `ARMSX2_HAS_LIBRASHADER`, `"[]"`
  para preset sem params.
- `setShaderChainParams(preset, names[], values[])` (4954-4991) → `GSDevice::SetShaderChainParams`.
  Não é gateado por `ARMSX2_HAS_LIBRASHADER` (o consumidor já é stub em build sem librashader).

**Declaração Kotlin/Java**: `ARMSX2/platforms/android/app/src/main/java/kr/co/iefriends/pcsx2/NativeApp.java:109,119`.

**UI** (pasta `ARMSX2/platforms/android/app/src/main/java/com/armsx2/`):
- `ui/common/ShaderChainSection.kt` — toggle + picker por **família/pastas** (não dialog; browser
  inline) com **pass count** por preset resolvido seguindo cadeias `#reference` (`resolvePasses`).
- `ui/common/ShaderManagerSection.kt` + `ShaderRepo.kt` — download de packs do buildbot
  (`shaders_slang.zip` ~51 MB + Retro Crisis GDV-NTSC), import SAF, nada embarcado no APK (licenças mistas).
- `ui/common/ShaderParamsEditor.kt` + `ShaderParams.kt` — editor de params (sliders, reset, save-as);
  `ShaderParams.pushEffective` envia TODOS os valores efetivos via `NativeApp.setShaderChainParams`.
- Hospedagem: `ui/settings/RendererTab.kt:428-440` (Settings→Renderer) e
  `ui/emulation/EmulationMenuScreen.kt:944-960` (menu in-game).
- Persistência: `config/Settings.kt:1336-1351` (`put("EmuCore/GS", "ShaderChainEnabled"/... )` +
  `ShaderParams.push` live).

**Fluxo completo preset:** UI → `Settings.applyTo` → `NativeApp.setSetting` → `commitSettings` →
`EmuConfig.GS.LoadSave` → `GSUpdateConfig` → `GSConfig` → `GSRenderer::Merge` → `ApplyShaderChain` →
`DoApplyShaderChain` (recria a chain se o path mudou, na render thread, live).

---

## 4. Estado atual GameNative (o que existe hoje)

### 4.1 Wrapper dlopen — `VulkanLibrashader`

- `app/src/main/cpp/winlator/VulkanLibrashader.h` — classe `VulkanLibrashader` com `loadLibrary`,
  `init`, `reloadPreset`, `isActive`, `setParam`, `applyFrame`, `destroyFilterChain`, `unloadLibrary`,
  `getLastError`. Ponteiros de função para a C API ABI 2 (`fnPresetCreateWithOptions`,
  `fnPresetCtxCreate/Free/SetAllowRotation`, `fnVkFilterChainCreate/Frame/Free/SetParam`). Um
  `std::mutex mtx` serializa tudo.
- `app/src/main/cpp/winlator/VulkanLibrashader.cpp`:
  - `loadLibrary()` (14-45): `dlopen("liblibrashader.so", RTLD_NOW|RTLD_GLOBAL)` + `dlsym`.
  - `reloadPreset(path)` (52-104): libera chain/preset/ctx antigos; `libra_preset_ctx_create` +
    `set_allow_rotation(&presetCtx,false)`; `libra_preset_create_with_options` com
    `libra_preset_opt_t{version=2}`; `libra_vk_filter_chain_create` com
    `filter_chain_vk_opt_t{version=2, frames_in_flight=3, force_no_mipmaps=false,
    use_dynamic_rendering=false, disable_cache=false}`. Observação: aqui a função está com
    `set_allow_rotation(&presetCtx, false)` — **passa o ponteiro do ponteiro** (ver seção 5, nota).
  - `setParam(name, value)` (106-112): locka `mtx` e chama `libra_vk_filter_chain_set_param` — **na
    thread de quem chamou** (ver 4.3).
  - `applyFrame(...)` (114-142): monta `libra_image_vk_t in/out`, `libra_viewport_t vp` (x,y,width,height
    do viewport), `frame_vk_opt_t{version=2, clear_history, aspect_ratio=0}`, e chama
    `fnVkFilterChainFrame(&chain, cb, frameCount, src, out, &vp, nullptr, &fopt)`.

### 4.2 Renderer — `VulkanRendererContext`

Arquivo: `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (2598 linhas) / `.h`.

Membros relevantes (`.h:428-470`): `libraShader`, `libraShaderEnabled/Active`, `libraShaderPresetPath`,
`libraNeedsHistoryClear`, `offscreenImage/View/FB/Mem`, `processedImage/View/Mem` (probes),
`filterOutputImage/View/Mem` (target do applyFrame), `atlasImage/View/Mem` + `atlasLayout` (fix atlas
**não usado no path default**), `filterOutputLayout` (tracking manual), `libraFrameCount`,
`filterCmdBuf/filterFence`, `blitPipeline/blitSampler/blitDS`, `filterSubmitMtx`.

**`renderFrame`** (`.cpp:1001-1464`) — path librashader (default, 1303-1390):

1. **Preset requests deferidos para a render thread** (1046-1079): fila `presetReqMtx`/`pendingPresetPath`
   (vinda do JNI) + hook `debug.gamenative.preset` (loop de teste). Consumido **no topo** do frame,
   ANTES do check `libraPath` (o primeiro load precisa rodar com `libraShaderActive` ainda `false`).
   Chama `libraShader.reloadPreset(presetToLoad)` na render thread (não na UI — evita crash Adreno).
2. `bool libraPath = libraShaderActive.load() && libraShaderEnabled.load();` (1081).
3. **Compositor** (1157-1171): `recordCompositorPass(cmdBufs[currentFrame], ...)` desenha as janelas no
   `offscreenImage`; submit + `WaitForFences(filterFence)` (submit do compositor separado).
4. `readbackOffscreenDiag()` (1174) — diag.
5. **Default path** (1303-1390): re-begin `filterCmdBuf`; transição `filterOutputImage` de
   `filterOutputLayout`→CAO; transição `offscreenImage` CAO→SRO; `libraShader.applyFrame(filterCmdBuf,
   libraFrameCount++, offscreenImage, ..., filterOutputImage, ..., viewport=superfície, clearHistory)`;
   transição `filterOutputImage` CAO→SRO; **present no MESMO CB**: begin render pass da swapchain
   (`renderPass`, framebuffer `swapchainFBs[imgIdx]`), update `blitDS` com `filterOutputView` em SRO,
   draw fullscreen, cursor dentro do mesmo pass; end render pass; restaura `filterOutputImage`
   SRO→CAO. `presentCB = filterCmdBuf`; `EndCommandBuffer` (1420); submit único com semáforo
   `imgAvail`/`renderDone` (1431-1445) + `QueuePresentKHR` (1458-1461).
6. **TEST MODEs** (1199-1250, gateados por `debug.gamenative.libradiag`): 2=AHB direto→swapchain,
   4=offscreen→swapchain, 5=filterOutput→swapchain; **P3/P4 probes** (1251-1302, env-gateados).

Funções de suporte: `createOffscreenTargets` (1788-1992, cria offscreen + processed + diag +
filterOutput + atlas + readback buffers), `recordFilterChainPass` (2123-2204, **código morto** — fix
atlas não usado no default), `presentAtlasToSwapchain` (2209-2233, **código morto**),
`readbackProcessedInFrame` (2241+), `transition` (594), `transferBarrierWide` (606, receita melonDS:
`srcAccess=MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`, `srcStage=ALL_COMMANDS`),
`blitImageToSwapchain`/`blitImageToSwapchainLayout` (2374/2379), `readbackOffscreenDiag` (2319).

### 4.3 JNI + UI do app

**JNI** (`app/src/main/cpp/winlator/vulkan_jni.cpp`): `nativeInitLibrashader` (267-273),
`nativeLoadLibrashaderPreset` (275-286, **deferred**: reporta sucesso, a render thread loga falhas reais),
`nativeEnableLibrashader` (288-292), `nativeSetLibrashaderParam` (294-302), `nativeGetLibrashaderError`
(304-309).

**Java** (`app/src/main/java/com/winlator/renderer/VulkanRenderer.java`):
- natives (136-140); init no `onSurfaceCreated` (185-196: se preset pendente, `nativeInitLibrashader`
  → `nativeLoadLibrashaderPreset` → params → enable).
- `setRetroArchShaderEnabled` (871-896), `loadRetroArchShaderPreset` (898-913),
  **`setRetroArchShaderParam`** (915-922): guarda no `pendingLibraShaderParams` E chama
  `nativeSetLibrashaderParam` **na UI thread** → `VulkanRendererContext::setLibrashaderParam`
  (`.cpp:1779-1781`) → `libraShader.setParam` (lock `mtx`, `libra_vk_filter_chain_set_param`).

**UI** (`app/src/main/java/app/gamenative/ui/component/ScreenEffectsPanel.kt`): seção "RetroArch
Shaders" (777-813) — toggle + **lista plana** de presets bundled (`friendlyName`/`categoryOf`),
aplicação via `ShaderImporter.importBundledPreset` + `VulkanRenderer.loadRetroArchShaderPreset` +
`setRetroArchShaderEnabled`. Persistência via `RetroArchShaderConfig` (`loadShaderConfig`/
`persistShaderConfig` no `Container`).

**Presets**: embarcados em `app/src/main/assets/retroarch/` (131 presets libretro do slang-shaders),
materializados para `filesDir/retroarch` e importados para `filesDir/retroarch_presets` pelo
`ShaderImporter.java`. Build do `.so`: `app/build.gradle.kts:40-80, 476-563` (cargo opcional →
`app/build/generated/librashader/jniLibs/<abi>/liblibrashader.so`, incl. header). CMake:
`app/src/main/cpp/CMakeLists.txt:50-73` (inclui `build/generated/librashader/include`).

### 4.4 Histórico: a saga da tela preta (NÃO reabrir)

Resumo de `docs/librashader-failed-attempts.md` — o path default atual só funciona porque 7 bugs foram
corrigidos em sequência (commit `06aef179`):
1. Import AHB com `VkExternalFormatANDROID` inválido → usar `fmtP.format` do driver (B8G8R8A8).
2. `offscreenRenderPass` com `finalLayout=SHADER_READ_ONLY` → restaurar `COLOR_ATTACHMENT_OPTIMAL` +
   transição CAO→SRO explícita antes de cada leitura (sampler do Adreno).
3. Atlas escrito por transfer amostra preto no Adreno → apresentar `filterOutputImage` direto (sem atlas).
4. Split de submissions (applyFrame num submit+wait, present noutro) nunca executava → tudo no mesmo CB.
5. Cursor num segundo render pass com `loadOp=CLEAR` apagava o frame → cursor dentro do pass do present.
6. Config persistida não aplicava no launch → `XServerScreen` aplica no renderer existente.
7. Crash cold-start com `appId` vazio → guard early-return.

**Lição registrada:** os "falhos de sampler/layout" antigos eram artefatos de um **deadlock de
command buffer** (EndCommandBuffer duplo). Não rediagnosticar isso.

---

## 5. Comparativo — onde o GameNative é inferior (Vulkan)

| # | Dimensão | ARMSX2 (referência) | GameNative (atual) | Veredito |
|---|---|---|---|---|
| 1 | **Acesso à chain** | params só na render thread; UI só grava no store (geração atômica); header explícito "UI NUNCA chama `set_param`" | `setRetroArchShaderParam` (UI thread) → `libraShader.setParam` → `libra_vk_filter_chain_set_param` direto sob `mtx` | **Inferior** — toca a C API de duas threads; se a UI chamar em cima de `applyFrame`, há janela fora do `mtx`? (aplica-se `mtx` no wrapper, mas a semântica "chain single-threaded" do ARMSX2 é mais forte) |
| 2 | **Falha do `applyFrame`** | `ApplyShaderChain` só troca `m_current` em sucesso → frame sem shader; latch de preset falho (não recompila 60x/s) | path default: se `applyFrame` falha, loga mas **apresenta `filterOutputImage`** (possivelmente lixo/parado, sobretudo 1º frame) | **Inferior** — risco de tela preta/lixo em preset instável; sem latch |
| 3 | **Tracking de layout** | tracker por textura (`GSTextureVK::Layout`) + `OverrideImageLayout` (resync sem barreira) + assert de conflito | tracking manual `filterOutputLayout` + transições explícitas CAO→SRO→CAO | **Quase equivalente** — funciona, mas sem o resync do tracker + assert de segurança |
| 4 | **Erros na UI** | `libra_error_write` → string completa; UI de params mostra | `getLastError` (string do wrapper) limitado; UI sem surfacing de erro do chain | **Inferior** (menor) |
| 5 | **UI / features** | packs baixáveis, famílias/pastas, pass count, editor de params com reset/save-as | lista plana de presets bundled, sem editor de params, sem download | **Inferior** (relevante p/ o release notes, mas fora do escopo Vulkan do renderer) |
| 6 | **Viewport/aspect** | chain roda no rect aspect-correto on-screen (CRT/scanline em densidade de tela) | target = superfície inteira (`offscreenImage` já é a resolução da tela) | **Equivalente** — arquiteturas diferentes; o compositor já renderiza na resolução da tela |
| 7 | **Submit/anel** | fix documentado p/ crash Adreno (1 submit por frame de chain; `NUM_COMMAND_BUFFERS=3`) | `applyFrame`+present no mesmo CB, 1 submit/frame; **não há** path de skip que destrave o anel | **Equivalente** — o bug específico não se aplica ao compositor |
| 8 | **Options da chain** | `libra_vk_filter_chain_create(&preset, vk, nullptr, ...)` (defaults: `frames_in_flight=3`, `use_dynamic_rendering=false`) | `filter_chain_vk_opt_t{version=2, frames_in_flight=3, ..., use_dynamic_rendering=false}` | **Equivalente** — GameNative mais explícito |
| 9 | **Preset reload** | recria a chain **na render thread**, lazy, por comparação de path a cada frame | reload **deferido para a render thread** via fila de requests | **Equivalente** — mesmo espírito |

**Ganhos concretos a portar (prioridade):** #1 (params na render thread), #2 (falha→fallback + latch),
#3 (resync de layout via tracker/Override). O resto é igual ou UI (fora do renderer).

> **Nota de ABI:** `libra_preset_ctx_t` é `struct _preset_ctx*` e a C API é
> `libra_preset_ctx_set_allow_rotation(libra_preset_ctx_t *context, bool value)` (pointer-to-pointer —
> `librashader/include/librashader.h:200, 2464`). O `&presetCtx` do GameNative
> (`VulkanLibrashader.cpp:65`) está **correto**. Antes de mudar o wrapper, conferir sempre o header do
> pin local (`librashader/include/librashader.h`), que é a fonte da assinatura real.

---

## 6. Checklist de port (executar em ordem; cada item tem aceite)

> Regras: só Vulkan; não mudar topologia/ABI/`use_dynamic_rendering`; não reintroduzir prebuilts;
> preservar os TEST MODEs e o `debug.gamenative.preset`. Sempre `git restore` de arquivo único se um
> passo regredir.

### Tarefa 1 — Params adiados (padrão geração), TAREFA PRINCIPAL
**Arquivos:** `app/src/main/cpp/winlator/VulkanLibrashader.h`, `VulkanLibrashader.cpp`,
`VulkanRendererContext.cpp` (só o ponto de consumo).

**1a. Store no wrapper** — adicionar a `VulkanLibrashader`:
```cpp
// .h (privado)
std::mutex paramStoreMtx;
std::vector<std::pair<std::string, float>> pendingParams;
std::atomic<uint64_t> paramGeneration{0};
uint64_t appliedGeneration = 0;
public:
void setParam(const std::string& name, float value);  // vira store (abaixo)
void applyPendingParams();                             // chama na render thread antes do applyFrame
```
```cpp
// .cpp — setParam vira store puro (sem tocar na chain)
void VulkanLibrashader::setParam(const std::string& name, float value) {
    std::lock_guard<std::mutex> lk(paramStoreMtx);
    bool found = false;
    for (auto& p : pendingParams) if (p.first == name) { p.second = value; found = true; break; }
    if (!found) pendingParams.emplace_back(name, value);
    paramGeneration.fetch_add(1, std::memory_order_release);
}
// .cpp — aplica os params pendentes na chain (render thread)
void VulkanLibrashader::applyPendingParams() {
    std::lock_guard<std::mutex> lk(mtx);            // serializa com applyFrame/reloadPreset
    if (!chain) return;
    std::vector<std::pair<std::string, float>> params;
    {
        std::lock_guard<std::mutex> lk2(paramStoreMtx);
        const uint64_t gen = paramGeneration.load(std::memory_order_acquire);
        if (gen == appliedGeneration) return;       // fast path: 1 load atômico por frame
        params = pendingParams;
        appliedGeneration = gen;
    }
    for (auto& [name, value] : params)
        if (libra_error_t err = fnVkFilterChainSetParam(&chain, name.c_str(), value))
            LLOG_E("librashader: set_param %s failed", name.c_str());
}
```
**1b. Consumo no renderer** — em `VulkanRendererContext.cpp`, imediatamente antes do `applyFrame` do
path default (`renderFrame`, antes da linha ~1316) e, para consistência, antes dos `applyFrame` dos
TEST MODEs:
```cpp
libraShader.applyPendingParams();
```
**1c. `applyPendingParams` também após `reloadPreset` bem-sucedido** — como a chain nova nasce com os
valores iniciais do preset, forçar `appliedGeneration = 0` dentro de `reloadPreset` (e zera
`pendingParams` não é necessário: o consumidor envia os últimos valores).

**Aceite:** `rg -n "fnVkFilterChainSetParam"` só aparece em `VulkanLibrashader.cpp`, chamado dentro de
`applyPendingParams`/`setParam` (nunca mais direto da UI). Compila. Sem crash com slider/param mexendo
durante o jogo.

### Tarefa 2 — Falha → fallback para o frame sem shader + latch
**Arquivos:** `VulkanRendererContext.h` (membro), `VulkanRendererContext.cpp` (path default).

**2a. Membro:**
```cpp
bool libraChainFailed = false;   // .h, junto aos membros libra*
```
**2b. Reset no reload:** em `renderFrame`, no bloco que processa preset (após `reloadPreset`), setar
`libraChainFailed = false`.

**2c. Path default** (`renderFrame`, bloco 1303-1390): capturar o retorno do `applyFrame` e, em falha,
**não apresentar `filterOutputImage`**; em vez disso fazer o blit do `offscreenImage` (que está em SRO
após a transição do passo anterior) para a swapchain — estrutura idêntica ao TEST MODE B (linhas
1202-1213):
```cpp
bool ok = libraShader.applyFrame(...);
if (!ok) {
    RLOG_E("librashader: applyFrame failed: %s", libraShader.getLastError().c_str());
    libraChainFailed = true;
    // fallback: apresenta o offscreen (frame sem shader) no mesmo CB
    // (offscreenImage já está em SRO; blit para swapchain via blitImageToSwapchain)
}
```
E no topo do bloco: `if (libraChainFailed) { /* blit offscreen direto, pula applyFrame/present do filterOutput */ }`.
Também registrar `libraChainFailed` no log `libraPath`/diag.

**Aceite:** com um preset quebrado (ex.: forçar `applyFrame` a falhar via preset inválido), o jogo
continua visível (sem shader), sem log spam de erro e sem tela preta.

### Tarefa 3 — Resync de layout equivalente ao `OverrideImageLayout`
O GameNative já rastreia `filterOutputLayout` e restaura CAO→SRO→CAO por frame (equivalentemente ao
`OverrideImageLayout`+`TransitionToLayout` do ARMSX2). Verificar e **documentar no código** o
invariante (comentário no path default, perto da transição final SRO→CAO):
- `filterOutputImage` está sempre em `COLOR_ATTACHMENT_OPTIMAL` no início de cada frame (UNDEFINED no 1º).
- `offscreenImage` está sempre em `COLOR_ATTACHMENT_OPTIMAL` ao sair do compositor; o path default o
  transiciona para SRO e **não** o restaura — conferir se um próximo `applyFrame` ou blit espera SRO
  de novo (manter comportamento atual; apenas comentar).

**Aceite:** nenhuma mudança de comportamento; apenas comentários/documentação + a confirmação de que os
TEST MODEs 4/5 continuam funcionando.

### Tarefa 4 — (Opcional, fora do renderer) UI de params
Replicar o editor do ARMSX2 usando `nativeGetLibrashaderError`/`nativeSetLibrashaderParam` existentes e
o `shaderPresetParams`-equivalente (o GameNative **não tem** um JNI de params do preset; seria preciso
adicionar `nativeGetLibrashaderParams(presetPath)` → JSON, espelhando `native-lib.cpp:4876-4952`).
**Fora do escopo desta sessão salvo pedido explícito.**

---

## 7. Verificação (executar antes de declarar sucesso)

```bash
./gradlew assembleModernDebug --no-daemon
adb install -r app/build/outputs/apk/modern/debug/*.apk
# 1. path sem shader: jogo visível, sem mudança de comportamento
# 2. preset simples: setprop debug.gamenative.preset /data/data/app.gamenative/files/retroarch_presets/misc/invert.slangp
#    -> tela negativa (verificação visual inequívoca)
# 3. preset de cor forte: .../retroarch_presets/film/technicolor.slangp -> média de brilho sobe
# 4. log: "preset chain active=1", "filter chain created", sem SIGSEGV, PID estável
# 5. loop automatizado:
python3 tools/shader-test-loop/shader_test_loop.py   # classifica BRIGHT/VISIBLE/BLACK/CHAIN_FAIL/CRASH
# 6. mexa em params ao vivo (após Tarefa 1): sem crash, slider aplica no frame seguinte
```

---

## 8. Referências

- **ARMSX2 (padrão):** `ARMSX2/` (clone raso, HEAD `6445e76`). Núcleo: seções 3.1-3.5 deste doc.
  - `ARMSX2/platforms/android/app/src/main/cpp/3rdparty/librashader/CMakeLists.txt`
  - `ARMSX2/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp:4657-4832` (`.h:567-571`)
  - `ARMSX2/pcsx2/GS/Renderers/Common/GSDevice.cpp:29-66, 1326-1352` (`.h:1551-1567, 1638-1652`)
  - `ARMSX2/pcsx2/GS/Renderers/Common/GSRenderer.cpp:313-345`
  - `ARMSX2/pcsx2/Config.h:1049-1053`; `ARMSX2/pcsx2/Pcsx2Config.cpp:1188-1189`
  - `ARMSX2/platforms/android/app/src/main/cpp/native-lib.cpp:4876-4992`
  - UI: `ARMSX2/platforms/android/app/src/main/java/com/armsx2/ui/common/*.kt`
- **GameNative (atual):**
  - `app/src/main/cpp/winlator/VulkanLibrashader.h/.cpp`
  - `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (default path 1303-1390; reload 1046-1079;
    targets 1788-1992; helpers 594-630; probes 1199-1302) e `.h`
  - `app/src/main/cpp/winlator/vulkan_jni.cpp:267-309`
  - `app/src/main/java/com/winlator/renderer/VulkanRenderer.java:871-934`
  - `app/src/main/java/app/gamenative/ui/component/ScreenEffectsPanel.kt:777-813`
  - `app/src/main/java/com/winlator/renderer/ShaderImporter.java`, `RetroArchShaderConfig.java`
  - `app/build.gradle.kts:40-80, 476-563`; `app/src/main/cpp/CMakeLists.txt:50-73`
- **Docs/histórico:** `docs/librashader-failed-attempts.md`, `docs/MILESTONES.md`,
  `docs/superpowers/plans/2026-08-01-librashader-black-screen-fix.md`, `docs/hypotheses/decision.md`.
- **librashader local:** `librashader/` (v0.12.0, pin `87e8a97`, ABI 2) — header
  `librashader/include/librashader.h`; doc da saída em `librashader-capi/src/runtime/vk/filter_chain.rs`
  (saída permanece em `COLOR_ATTACHMENT_OPTIMAL`, caller faz a transição final).
- **Ferramenta de teste:** `tools/shader-test-loop/shader_test_loop.py`.

---

## 9. Execução do checklist (2026-08-07, commit 9c622426)

**Tarefa 1 (params adiados)** — FEITO. `setParam` virou store puro (`paramStoreMtx`/`pendingParams`/
`paramGeneration`/`appliedGeneration`); `applyPendingParams()` roda na render thread antes do
`applyFrame` (fast path: 1 load atômico). `rg "fnVkFilterChainSetParam"` → só em
`VulkanLibrashader.cpp` (dentro de `applyPendingParams`). UI nunca toca a chain.

**Tarefa 2 (falha → fallback + latch)** — FEITO. `libraChainFailed` + apresentação do offscreen
sem shader no mesmo CB (estrutura TEST MODE B) em falha do `applyFrame` e em latch; reset no reload.
Extra hardening: `reloadPreset` agora é **create-first swap** (chain nova construída antes de liberar
a antiga — create falho mantém o shader anterior rodando).

**Tarefa 3 (resync de layout)** — Documentado (invariantes no path default). Comportamento mantido.

**Sistema de feedback (pedido do usuário)** — renderLoop loga exceções (o `catch(...)` era mudo —
foi isso que escondeu o freeze de 2026-08-07); waits de fence com timeout de 500ms + skip de frame
(guarda de GPU wedged — o freeze real do usuário foi um `WaitForFences` infinito após um create
falho); logs de path por frame (DEFAULT ok / NON-LIBRA / latched, throttled); log de diagMode no
startup (props stale `debug.gamenative.libradiag` desviavam para TEST MODE silenciosamente); hook
`debug.gamenative.preset` valida existência do arquivo (props stale não sequestram o request da UI).

**Verificação on-device (Mi 11/Adreno 650)**:
- megatron HDR: cria 2x seguidas sem falha (create-first) ✓
- invert via hook: STRONG_CHANGE (mean 253, 128/128 conteúdo) ✓
- technicolor: CHANGED (Δmean +37 vs baseline) ✓
- loop automatizado cena-independente (delta-vs-baseline + pipeline health): 17/17 sem erros de
  pipeline; zero latch/timeout/throw; loop vivo a 60fps ✓
- prop inválida (`/data/nope.slangp`): rejeitada com log, chain anterior continua ✓
- **Lição**: a "tela preta" do usuário com os presets megatron/MMJ era majoritariamente **cena escura
  legítima** (jogo parado numa tela ~RGB(10,10,10); invert/technicolor provam o pipeline) — o
  classificador absoluto antigo produzia falsos positivos de BLACK.
