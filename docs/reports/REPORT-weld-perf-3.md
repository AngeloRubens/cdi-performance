# Report 3: performance di Weld, terzo giro (ottobre 2026)

Segue `/home/lop/cdi/REPORT-weld-perf-2.md`. Build, test, profilazioni e benchmark sono girati **solo su GitHub Actions** dei fork `AngeloRubens/*`. Nessuna PR, issue o ticket upstream: le bozze sono in `/home/lop/cdi/cdi-performance/docs/`.

**In breve.** Delle tre modifiche richieste ne è stata tenuta una sola: lo stack di interception basato su array.
- methodNotIntercepted: +30 % (Weld 7 da 46,3 a 60,1 ops/µs, Weld 6 da 46,4 a 61,5).
- methodIntercepted: +12–14 %.
- Nessun cambio di semantica.
- CI con test e CDI TCK verde su entrambi i branch.

La seconda `ThreadLocal.get()` resta: toglierla in modo sicuro richiede di cambiare la generazione del bytecode. È stata analizzata ma non fatta. Il punto 3 (altre micro-ottimizzazioni) è stato saltato.

## 1. Link

| cosa | link |
|---|---|
| Weld 7 | https://github.com/AngeloRubens/core/tree/perf/proxy-3 (commit 234c3cf + `[fork-only]` e8961f9, sopra perf/proxy-2) |
| Weld 6 backport | https://github.com/AngeloRubens/core/tree/perf/proxy-3-6.0 (f915a75 + `[fork-only]` a9e2421, sopra perf/proxy-2-6.0) |
| CI+TCK perf/proxy-3 | https://github.com/AngeloRubens/core/actions/runs/37187706212 |
| CI+TCK perf/proxy-3-6.0 | https://github.com/AngeloRubens/core/actions/runs/37187728582 |
| Benchmark incrementale | https://github.com/AngeloRubens/cdi-performance/actions/runs/37187639208 |
| **Benchmark completo** | https://github.com/AngeloRubens/cdi-performance/actions/runs/37188810113 |
| Profilazione dello stato finale | https://github.com/AngeloRubens/cdi-performance/actions/runs/37188800246 |
| Bozza PR | https://github.com/AngeloRubens/cdi-performance/blob/jakarta-2026/docs/weld-pr-proxy-3-draft.md |

- Tag del fork Weld: `bench/p3-a` (= 234c3cf). Il riferimento per proxy-2 è `bench/p2-f2g` (= 49d2757).
- Il primo push di perf/proxy-3 non compilava (run 37187536101). È stato corretto con amend + force-push sul fork prima di qualsiasi misura.

## 2. Modifica tenuta: stack con array + indice

**Prima.** `InterceptionDecorationContext.Stack` incapsulava un `ArrayDeque`. Il profilo di proxy-2 su methodNotIntercepted mostrava circa il 45 % in push/pop: aritmetica circolare della deque e `isEmpty()` aggiuntivi.

**Dopo.** Un array `CombinedInterceptorAndDecoratorStackMethodHandler[]` più un `size`.
- Capacità iniziale 4, che raddoppia con `Arrays.copyOf`.
- `startIfNotOnTop` confronta direttamente `elements[size-1]`.
- `pop` azzera lo slot, così non trattiene handler o istanze.
- Il design a riferimento debole del giro precedente resta invariato: nessun leak di classloader.

**Il bytecode generato, l'API pubblica e la semantica non cambiano.**
- **Self-invocation, `NULL_INSTANCE`, chiamate annidate e decorator:** nessuna differenza.
- **Percorsi con eccezione e `toString()`:** nessuna differenza.
- **Eccezioni su stack vuoto:** le stesse di prima.
- **Elemento `null`:** lancia `NullPointerException` come prima, ma ora prima di toccare l'holder. Prima l'holder restava marcato attivo su uno stack vuoto: era un piccolo bug latente.

**Nuovo test unitario:** `InterceptionDecorationContextTest`. Copre ordine di push/pop, `NULL_INSTANCE`, crescita oltre 4 elementi, eccezioni e riuso dello stack.

Il backport su Weld 6 è identico: cherry-pick senza conflitti.

## 3. Evitare la seconda `ThreadLocal.get()`: analizzato, non fatto

**Perché le varianti economiche non sono sicure:**
- tra client proxy e subclass passano solo gli argomenti del metodo e lo stato per thread;
- la subclass viene chiamata anche direttamente (self-invocation, `@Dependent`, decorator, reflection), quindi non può dare per scontato che il lookup sia già stato fatto;
- una cache "ultimo thread" sull'handler ha race, oppure, resa sicura, crea contesa tra i thread;
- un contatore globale richiede operazioni atomiche contese;
- `ScopedValue` non è disponibile sulla JDK minima.

**Pista trovata.** Per i metodi **non intercettati** di una subclass intercettata, il marker `NULL_INSTANCE` del client proxy non è osservabile: alla fine la cima dello stack è comunque l'handler della subclass. Il client proxy potrebbe quindi saltare `startIfNotEmpty()` per quei metodi.

Per farlo però `ClientProxyFactory` deve conoscere l'insieme esatto dei metodi che `InterceptedSubclassFactory` intercetta: interceptor e decorator, metodi bridge, default e privati, più l'handler `null` durante la costruzione. È lavoro sulla generazione del bytecode, con rischio di disallineamento tra le due factory.

## 4. Micro-ottimizzazioni: saltate

Nel profilo finale non è rimasto nulla di rapido e sicuro:
- su methodNotIntercepted il costo è nei lookup della ThreadLocal e nelle store su `holder[ACTIVE]`;
- su methodIntercepted pesano la reflection (`Method.invoke`) e una `ConcurrentHashMap.get` in `getInterceptionChain`.

## 5. Benchmark incrementale (run 37187639208)

Condizioni: modalità rapida, 3 fork × 4 iterazioni da 1 s, 1 thread, ops/µs. A e B sono due misure della stessa ref; il runner era più veloce di quello del run completo.

| benchmark | proxy-2 A | proxy-2 B | **proxy-3 A** | **proxy-3 B** | Δ |
|---|---|---|---|---|---|
| methodNotIntercepted | 55,3 ± 0,1 | 55,4 ± 0,1 | **70,0 ± 0,3** | **70,1 ± 0,1** | **+26,6 %** |
| methodIntercepted | 20,2 ± 0,4 | 20,2 ± 0,3 | **22,4 ± 0,7** | **21,4 ± 1,6** | +6…11 % |
| applicationScoped | 422,7 | 423,2 | 421,6 | 419,9 | ≈ 0 |
| applicationScopedInterceptionUsed | 266,4 | 266,1 | 267,0 | 267,2 | ≈ 0 |
| requestScoped | 168,6 | 172,6 | 169,0 | 171,5 | ≈ 0 |

## 6. Benchmark completo (run 37188810113)

Condizioni: stesso runner, 2 fork × 5 iterazioni da 2 s, ops/µs, 1 thread.

| benchmark | OWB 4.1.1 | Weld 7.0.0 | 7 proxy-2 | **7 proxy-3** | Weld 6.0.4 | 6 proxy-2 | **6 proxy-3** |
|---|---|---|---|---|---|---|---|
| methodNotIntercepted | 505,7 ± 0,5 | 9,7 | 46,3 ± 0,6 | **60,1 ± 2,4** (+30 %) | 9,7 | 46,4 ± 0,1 | **61,5 ± 0,3** (+32 %) |
| methodIntercepted | 9,9 ± 0,1 | 6,7 | 16,6 ± 0,4 | **19,0 ± 0,1** (+14 %) | 7,1 | 16,4 ± 0,0 | **18,4 ± 0,8** (+12 %) |
| classIntercepted | 10,0 | 7,1 | 16,7 | **18,3** (+9 %) | 7,1 | 16,6 | **17,8** (+7 %) |
| applicationScoped | 713,7 | 19,9 | 326,4 | 324,4 | 20,3 | 326,5 | 325,8 |
| applicationScopedInterceptionUsed | 718,6 | 20,4 | 199,5 | 199,5 | 20,4 | 200,2 | 199,7 |
| requestScoped | 90,4 | 10,6 | 142,6 | 141,4 | 10,1 | 142,8 | 142,5 |
| fireEvent (non toccato) | 3,1 | 43,3 | 43,6 | 41,8 | 41,0 | 44,4 | 41,7 |
| boot+shutdown ms | 43,1 | 45,4 | 45,6 | 45,6 | 46,5 | 46,4 | 48,2 |

Weld 7 proxy-3 rispetto a 7.0.0: methodNotIntercepted x6,2, methodIntercepted x2,8.

**4 thread:**
- **methodNotIntercepted:** +23 % (Weld 7 da 115,3 a 142,2; Weld 6 da 116,8 a 142,8). OWB fa 1032.
- **methodIntercepted e classIntercepted:** troppo rumorosi (±7–10). Per esempio Weld 6 proxy-3 methodIntercepted è 25,0 ± 10,7 contro 35,9 ± 2,9 di proxy-2. Probabilmente è rumore, ma **non è verificato**.

## 7. Profilo dello stato finale (run 37188800246)

- **methodNotIntercepted** (circa 0 B/op):
  - circa 35–45 % nei due lookup della ThreadLocal;
  - `getStack` 17 %;
  - `Stack.push` 17 %, probabilmente per le store con barriere GC (non verificato);
  - `pop` 7 %;
  - `ArrayDeque` non compare più.
- **methodIntercepted** (64 B/op):
  - reflection circa 20 %;
  - stack circa 15 %;
  - ThreadLocal circa 17 %;
  - `getInterceptionChain` circa 8 %.
- **applicationScopedInterceptionUsed:** circa 54 % è l'unica `ThreadLocal.get()` del client proxy.

## 8. Test e TCK (solo JDK 21), 0 failure e 0 error

| job | 7.x perf/proxy-3 (37187706212) | 6.0 perf/proxy-3-6.0 (37187728582) |
|---|---|---|
| test senza container | 3268 (+1 nuovo) | 2808 (+1) |
| in-container (WildFly) | 2087 | 1812 |
| **CDI TCK (WildFly)** | **2089** | **1903** |
| CDI TCK SE | 36 | 34 |
| relaxed mode | 5343 | 4698 |
| signature test | ok | job assente nella CI 6.0 |

**Non eseguiti:** JDK 17 e 25.

## 9. Rischi

- **Basso:** la modifica è locale a una classe e il bytecode generato non cambia.
- **Dipendenza:** richiede le PR precedenti (client-proxy, proxy-2), con i loro rischi (`SwitchPoint`, nessun test di invalidazione concorrente).
- **Crescita dell'array:** non si restringe mai. È lo stesso comportamento di `ArrayDeque`.
- **Elemento `null`:** l'eccezione ora arriva prima di toccare l'holder. È più corretto, ma è una differenza.

## 10. Idee scartate

- Cache per handler dello stack dell'ultimo thread.
- Contatore globale di stack attivi.
- Riferimento forte allo stack inattivo (leak di classloader).
- Togliere `holder[ACTIVE]`: non funziona con `MethodInvocationStrategy`.
- `ScopedValue`.

## 11. Gap residuo con OWB

- **methodNotIntercepted:** x8,4 (era x10,9).
- **applicationScoped:** x2,2 senza interception in uso, x3,6 con interception in uso. Invariato.
- **Dove Weld è più veloce di OWB:** methodIntercepted x1,9, classIntercepted x1,8, requestScoped x1,6, fireEvent x14.

## 12. Prossimi passi

1. Client proxy che salta `startIfNotEmpty()` per i metodi non intercettati delle subclass intercettate (sezione 3).
2. Analisi del bytecode dei metodi non intercettati dietro una proprietà di configurazione, più discussione con i maintainer sulla semantica della self-invocation. È l'unica strada verso i numeri di OWB.
3. Per methodIntercepted: interceptor invocati via `MethodHandle` invece di `Method.invoke`, e cache della catena di interception per metodo.
4. CI con JDK 17 e 25.
5. Dopo la revisione:
   - ticket WELD;
   - PR nell'ordine client-proxy, proxy-2, proxy-3;
   - rimozione dei commit `[fork-only]`.

## 13. File aggiornati

- **cdi-performance** (jakarta-2026, commit 09658cb e 443cd25): `.github/weld-refs`, `docs/weld-pr-proxy-3-draft.md` (nuovo), `readme.md`. Branch di servizio `ci/bench-quick` e `ci/profile`.
- **weld-core:** `InterceptionDecorationContext.java`, `InterceptionDecorationContextTest.java` (nuovo).
