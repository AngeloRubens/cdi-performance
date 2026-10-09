# Report 2: performance di Weld, secondo giro (ottobre 2026)

Segue `/home/lop/cdi/REPORT-weld-perf.md`. Build, test, profilazioni e benchmark sono girati **solo su GitHub Actions** dei fork `AngeloRubens/*`. Nessuna PR, issue o ticket upstream: i testi sono bozze in `/home/lop/cdi/cdi-performance/docs/`.

## 1. Link

| cosa | link |
|---|---|
| Weld 7 | https://github.com/AngeloRubens/core/tree/perf/proxy-2 (commit 7a04f76, 80d3052, 49d2757 + `[fork-only]` in cima) |
| Weld 6 backport | https://github.com/AngeloRubens/core/tree/perf/proxy-2-6.0 (82c6d70, 4cbcec4, 4af4f37 + `[fork-only]`) |
| Esperimento scartato | https://github.com/AngeloRubens/core/tree/perf/exp-no-override |
| CI+TCK perf/proxy-2 | https://github.com/AngeloRubens/core/actions/runs/37141452254 |
| CI+TCK perf/proxy-2-6.0 | https://github.com/AngeloRubens/core/actions/runs/37141452229 |
| CI+TCK prima versione con flag statico | https://github.com/AngeloRubens/core/actions/runs/37134570396 (7.x), https://github.com/AngeloRubens/core/actions/runs/37134653164 (6.0) |
| CI esperimento no-override (fallisce, atteso) | https://github.com/AngeloRubens/core/actions/runs/37134570418 |
| Benchmark incrementale 1 (2 fork) | https://github.com/AngeloRubens/cdi-performance/actions/runs/37134628349 |
| Benchmark incrementale 2 (3 fork, flag statico e SwitchPoint) | https://github.com/AngeloRubens/cdi-performance/actions/runs/37136289879 |
| **Benchmark completo** | https://github.com/AngeloRubens/cdi-performance/actions/runs/37141471463 |
| Profilazione dello stato finale | https://github.com/AngeloRubens/cdi-performance/actions/runs/37141492408 |
| Nota di design | https://github.com/AngeloRubens/cdi-performance/blob/jakarta-2026/docs/weld-self-invocation-redesign.md |

Tag del fork usati per i benchmark incrementali: `bench/p2-e`, `bench/p2-eg`, `bench/p2-f` (flag statico, scartato), `bench/p2-f2`, `bench/p2-f2g`, `bench/p2-exp`, `bench/p2-6`.

## 2. Modifiche

### 2.1 methodNotIntercepted (gap di 14x con OWB)

**Come funziona oggi Weld.** La subclass intercettata fa l'override anche dei metodi **non** intercettati. Il corpo generato è sempre:
1. `startIfNotOnTop(handler)`;
2. `super.m()`;
3. `end()`.

Questo push serve a non intercettare `this.metodoIntercettato()` quando viene chiamato da dentro un metodo non intercettato.

**Come fa OWB 4.1.1** (verificato in `InterceptorDecoratorProxyFactory`):
- usa un proxy **delegante**: il campo `owbIntDecProxiedInstance` contiene un'istanza separata;
- i metodi non intercettati sono solo `getfield` + `invokevirtual`;
- dentro il bean `this` è l'istanza "pulita", quindi la self-invocation non viene mai intercettata e non serve stato per thread.

**Esperimento "nessun override"** (`perf/exp-no-override`, scartato):
- methodNotIntercepted passa da 55 a **233 ops/µs** (x4,2);
- il CDI TCK resta verde;
- **falliscono 3 test di Weld** che fissano questa semantica come contratto:
  - `SelfInvocationTest.testSelfInvocationOfInterceptedMethod`;
  - `SelfInvocationInterceptionTest.testDirectMethodInvocation`;
  - `SelfInjectionBeanTest.testMethodB`.

Cambiare questo comportamento richiede una decisione dei maintainer (alternativa 4 nella nota di design).

**Tenuta: (e) stack per thread riusato (7a04f76).**
- La ThreadLocal contiene un holder `Object[]`. L'holder punta allo stack in modo forte solo mentre lo stack non è vuoto, altrimenti tramite `WeakReference`.
- Nessun leak di classloader, nessuna allocazione e nessun `ThreadLocal.set` per chiamata.
- La registrazione nella `RequestScopedCache` (WELD-1812) non serve più.
- Il bytecode generato è identico. Unica differenza: la `peek()` statica su uno stack vuoto lancia `EmptyStackException`, mentre prima poteva restituire `null`. Il metodo non ha chiamanti in Weld.

Effetti:
- methodNotIntercepted +20 % (38,5 → 46,3);
- methodIntercepted +5 %;
- allocazione di methodIntercepted da 152 a 64 B per chiamata.

Profilo finale di methodNotIntercepted:
- circa 45 % nel push/pop dello stack;
- circa 25 % in due `ThreadLocal.get()`, una nel client proxy e una nella subclass.

### 2.2 (b) Cache dell'istanza @ApplicationScoped

**Perché non una cache vera.** `ApplicationScopedContextualInstanceStrategy` ha già un campo volatile, e `destroy()` lo azzera: è l'unica invalidazione corretta. Una cache nel proxy dovrebbe comunque rileggere quel volatile.

OWB (`cachedInstance`) usa un campo non volatile che non viene mai invalidato: dopo `AlterableContext.destroy()` continua a usare l'istanza distrutta.

**Tenuta: (g) (49d2757).**
- `ContextBeanInstance` tiene il bean come `RIBean` (campo transient, ricreato da `readResolve`).
- Chiama `ContextualInstance.getIfExists(RIBean, …)`, così salta l'`instanceof`.
- L'invalidazione resta invariata.

Effetti: applicationScoped +7…14 %, requestScoped circa +4 %.

### 2.3 (f) Flag "nessuna interception in uso" (80d3052)

**Condizione.** Si usa "nessun thread ha mai creato un holder di stack". In quel caso `startIfNotEmpty()` restituirebbe sempre `null`, quindi saltare la lookup è esattamente equivalente al codice di prima.

**Prima versione, flag `static boolean`: scartata.**
- +46 % senza interceptor;
- **-8 %** con interceptor in uso.

**Versione finale, `SwitchPoint`.**
- Un `MethodHandle` `static final` restituisce `null` finché il `SwitchPoint` è valido.
- Al primo holder creato c'è una sola deottimizzazione.
- Il JIT elimina la guardia, quindi con interceptor in uso non si paga niente.

| run 37136289879, ops/µs | request-cache | e | e+g | e+flag statico | e+f | **e+f+g** |
|---|---|---|---|---|---|---|
| NoInterceptor.applicationScoped | 187,2 | 199,9 | 214,3 | 291,3 | 285,3 | **326,7** |
| applicationScopedInterceptionUsed | 187,2 | 188,5 | 199,7 | 173,7 | 194,0 | **199,2** |
| NoInterceptor.requestScoped | 106,5 | 111,7 | 116,7 | 131,3 | 138,4 | **142,2** |
| methodIntercepted | 15,6 | 16,5 | 16,6 | 16,2 | 16,5 | **16,7** |
| methodNotIntercepted | 38,5 | 46,3 | 46,4 | 45,4 | 44,5 | **46,5** |

**Attenzione: `CdiBenchmark.applicationScoped` è un caso migliore.** Nella sua JVM nessun metodo intercettato viene mai chiamato, quindi beneficia di (f). Il numero realistico per un'applicazione con interceptor è `applicationScopedInterceptionUsed`.

**Nuovo `CdiNoInterceptorBenchmark`:**
- container con `disableDiscovery()` e solo i bean semplici;
- variante `applicationScopedInterceptionUsed`: deployment normale, dopo una chiamata intercettata nel setup.

**Rischi di (f):**
- `SwitchPoint` è un meccanismo nuovo per Weld core;
- in WildFly un solo deployment che usa interceptor disattiva la scorciatoia per tutti (scelta conservativa);
- manca un test specifico per l'invalidazione concorrente.

### 2.4 Nota di design

`docs/weld-self-invocation-redesign.md` contiene:
- perché serve il push di `NULL_INSTANCE`, con il caso A(@Dependent, intercettato) → proxy B → A diretto;
- come lo evita OWB;
- 9 alternative con guadagno e rischio stimati;
- 6 domande per i maintainer.

## 3. Benchmark completo (run 37141471463)

Condizioni: stesso runner, 2 fork × 5 iterazioni da 2 s, ops/µs, 1 thread.

| benchmark | OWB 4.1.1 | Weld 6.0.4 | Weld 6 req-cache | **Weld 6 proxy-2** | Weld 7.0.0 | Weld 7 main | Weld 7 req-cache | **Weld 7 proxy-2** |
|---|---|---|---|---|---|---|---|---|
| applicationScoped | 718,9 ± 2,3 | 19,8 | 187,1 | **326,9 ± 4,1** | 19,8 | 19,1 | 187,1 | **327,1 ± 6,3** |
| applicationScopedInterceptionUsed | 717,4 ± 4,6 | 20,4 | 187,5 | **199,7 ± 0,5** | 20,0 | 19,9 | 187,4 | **199,3 ± 1,1** |
| requestScoped | 90,8 ± 1,8 | 10,4 | 106,5 | **141,2 ± 0,6** | 10,5 | 10,6 | 106,4 | **135,8 ± 8,7** |
| classIntercepted | 10,1 | 7,1 | 16,0 | **17,1** | 7,1 | 7,1 | 15,9 | **16,5** |
| methodIntercepted | 9,7 ± 0,6 | 7,1 | 15,4 | **16,7 ± 0,1** | 7,1 | 7,1 | 14,9 | **16,8 ± 0,7** |
| methodNotIntercepted | 505,2 ± 0,9 | 9,3 | 38,5 | **46,3 ± 0,3** | 9,7 | 9,6 | 38,6 | **46,5 ± 0,1** |
| fireEvent (non toccato) | 3,1 | 44,8 | 39,3 | 43,4 | 43,5 | 43,5 | 43,4 | 42,9 |
| boot+shutdown ms | 46,2 | 47,7 | 51,2 | 47,3 | 45,5 | 46,6 | 45,4 | 50,7 ± 3,1 |

- **Weld 7 proxy-2 rispetto a 7.0.0:** applicationScoped x16,6, requestScoped x13, methodNotIntercepted x4,8, methodIntercepted x2,4. Weld 6 proxy-2 ha guadagni uguali.
- **Weld 7 main non patchato ≈ 7.0.0** (x0,97–1,01): i commit upstream successivi al tag non spiegano nessun guadagno.
- **Boot di proxy-2:** 50,7 ms contro 45,5 ms. Gli intervalli quasi si sovrappongono e Weld 6 proxy-2 è a 47,3 ms, quindi probabilmente è rumore. Non verificato.

**4 thread** (runner con 4 vCPU condivise):

| benchmark | proxy-2 | request-cache | OWB |
|---|---|---|---|
| applicationScoped | 670,2 | 432,5 | 1343 |
| requestScoped | 307,3 | 231,6 | 223,5 |
| methodNotIntercepted | 118,4 | 35,6 | 1031 |
| methodIntercepted | 33,0 | 23,3 | 22,1 |

## 4. Test e TCK (solo JDK 21), 0 failure e 0 error

| job | 7.x perf/proxy-2 (37141452254) | 6.0 perf/proxy-2-6.0 (37141452229) |
|---|---|---|
| test senza container | 3267 | 2807 |
| in-container (WildFly) | 2086 | 1811 |
| **CDI TCK (WildFly)** | **2089** | **1903** |
| CDI TCK SE | 36 | 34 |
| relaxed mode | 5342 | 4697 |
| signature test | ok | job assente nella CI 6.0 |

**Non eseguiti:** JDK 17 e 25. Non sono stati scritti test unitari nuovi.

## 5. Gap residuo con OWB

- **applicationScoped:** x2,2 senza interception in uso (era circa x4); x3,6 con interception in uso, perché resta una `ThreadLocal.get()` per chiamata.
- **methodNotIntercepted:** x10,9, dovuto alla semantica di Weld sulla self-invocation.
- **Dove Weld è più veloce di OWB:** requestScoped (x1,5), methodIntercepted (x1,7), classIntercepted (x1,7), fireEvent (x14).

## 6. Prossimi passi

1. Stack più leggero (array + indice) e passaggio dello stack dal client proxy alla subclass. Stima: +30–60 % su methodNotIntercepted, senza cambi di semantica.
2. Prototipo dell'analisi del bytecode dei metodi non intercettati, dietro una proprietà di configurazione.
3. Discutere con i maintainer la semantica della self-invocation.
4. CI con JDK 17 e 25.
5. Dopo la revisione:
   - ticket WELD;
   - PR nell'ordine client-proxy, proxy-2, request-cache (separata);
   - rimozione dei commit `[fork-only]`.

## 7. File aggiornati in cdi-performance (branch jakarta-2026)

- `docs/weld-self-invocation-redesign.md` (nuovo)
- `docs/weld-pr-proxy-2-draft.md` (nuovo)
- `docs/weld-pr-6.0-draft.md` (aggiornato)
- `readme.md`
- `CdiNoInterceptorBenchmark.java`
- `scripts/bench.sh`, `scripts/report.py`
- `.github/weld-refs`, `benchmark.yml`, `profile.yml`

I branch `ci/bench-quick` e `ci/profile` del fork contengono commit di servizio per i run incrementali.
