# Weld proxy-4: invocazione degli interceptor (ottobre 2026)

## Candidato

Ripresa e completamento del quarto giro lasciato da Claude. Codice e backport sono completati, le CI e i benchmark sono verdi e le due PR upstream sono aperte.

| versione | branch | commit |
|---|---|---|
| Weld 7 | perf/proxy-4 | 045e4fc14e7f947967569353742a69d42535928b |
| Weld 6 | perf/proxy-4-6.0 | 9c00639608a654a25454a533cc1bbbdc2413c0da |

## Modifiche

1. Invoker condivisi per classe tramite ClassValue: MethodHandle adattati, conversioni controllate, fallback alla reflection, eccezioni del metodo avvolte in InvocationTargetException.
2. LambdaMetafactory per metodi di istanza con ritorno non-void e zero parametri o un parametro di riferimento. Le Function/BiFunction ottenute possono essere inlined dal JIT; se il lookup non riesce restano handle o reflection.
3. Percorso rapido per una catena già inizializzata e cache della prima catena per istanza, con fallback alla ConcurrentHashMap per gli altri metodi. Inizializzazioni concorrenti possono scegliere catene diverse ma valide.
4. Campi timer/constructor rimossi dal contesto around-invoke; il costruttore resta nel contesto around-construct. Array vuoto degli argomenti condiviso.
5. proceed() riusa lo stack sul thread proprietario. Su un altro thread continua a usare il lookup dello stack di quel thread.

Non cambia intenzionalmente la semantica della self-invocation. Il backport Weld 6 conserva WeldInvocationContext e il suo context data con gli interceptor bindings.

## Correzione durante la ripresa

Method.equals() non distingue il flag di accessibilità. Un handle creato da un Method con setAccessible(true) poteva essere riusato per un Method uguale senza quell'override, o dopo setAccessible(false). Il percorso con handle/lambda verifica ora l'override quando necessario, altrimenti passa alla reflection e ai suoi controlli.

Aggiunti test per copie di Method con accessibilità diversa, revoca dell'override, invocazioni senza argomenti e con argomento singolo/array, e riuso dello stack sullo stesso thread o su un altro thread.

## Evidenza incrementale

Primo run [37204192518](https://github.com/AngeloRubens/cdi-performance/actions/runs/37204192518): tre fork, quattro misure da un secondo, un thread. I soli MethodHandle non mostrano un guadagno significativo; cache e allocazioni portano methodIntercepted da 22,39 ± 0,46 a 28,32 ± 0,47 ops/µs (+26,5%). Le metriche sono salvate in `weld-proxy-4-incremental-results.json` con hash dei JSON originali.

Secondo run [37205424057](https://github.com/AngeloRubens/cdi-performance/actions/runs/37205424057), su un runner diverso dal primo:

| variante | methodIntercepted A | ripetizione B | classIntercepted A | ripetizione B |
|---|---:|---:|---:|---:|
| stack riusato + reflection | 22,59 ± 0,93 | 22,75 ± 0,45 | 22,85 ± 0,67 | 21,49 ± 1,83 |
| stack riusato + MethodHandle | 23,87 ± 1,51 | 24,58 ± 0,35 | 24,61 ± 0,30 | 24,37 ± 0,18 |
| stack riusato + lambda | 34,88 ± 1,05 | 33,93 ± 0,61 | 35,04 ± 1,80 | 34,62 ± 1,27 |

Questo confronto giustifica mantenere la specializzazione lambda. Non si devono confrontare i valori assoluti tra i due run. La copia delle metriche del secondo è `weld-proxy-4-attribution-results.json`. Entrambi i run precedono la correzione dell'accessibilità; il confronto finale misura i commit corretti.

## Validazione finale

- [Test mirati + formatter, 37281149853](https://github.com/AngeloRubens/cdi-performance/actions/runs/37281149853): **10 test per versione, zero failure/error/skip**, JDK 17. Sorgenti formattati nel runner, patch recuperata e committata.
- [Weld 7 test e TCK, 37281293309](https://github.com/AngeloRubens/core/actions/runs/37281293309): **successo, tutti i job verdi**.
- [Weld 6 test e TCK, 37281293013](https://github.com/AngeloRubens/core/actions/runs/37281293013): **successo, tutti i job verdi**.
- [Benchmark completo, 37281326000](https://github.com/AngeloRubens/cdi-performance/actions/runs/37281326000): **successo**, un runner per thread count, tutte le varianti sequenziali.
- [Profilazione, 37281324917](https://github.com/AngeloRubens/cdi-performance/actions/runs/37281324917): **successo**, SHA Weld verificato nel log: 045e4fc.
- [Avvio a freddo, 37281596417](https://github.com/AngeloRubens/cdi-performance/actions/runs/37281596417): **successo**, 10 JVM nuove per variante.

I primi run della ripresa, 37280824427 e 37280825043, si sono fermati sulla formattazione di una riga del guard di accessibilità, prima di compilare. Corretti con l'output del formatter ufficiale eseguito in CI. Il benchmark 37281004627 è stato annullato per sostituire gli SHA non formattati con quelli finali.

## Metodo di misura

- Throughput 1 thread: 2 fork, 3 warmup da 2 s, 5 misure da 2 s.
- Throughput 4 thread: 3 fork, 3 warmup da 2 s, 7 misure da 2 s (più campioni rispetto al giro precedente).
- Ogni numero di thread ha un proprio runner; tutte le implementazioni di quel confronto girano sequenzialmente sullo stesso runner. Non si deduce lo scaling 1→4 confrontando macchine diverse.
- Baseline: OWB 4.1.1, Weld 7.0.0 e 6.0.4; proxy-3 e proxy-4 per Weld 7 e 6. Gli SHA Weld sono fissati in .github/weld-refs e registrati nei log di build.
- Boot/shutdown riscaldato: 3 fork, 10 warmup, 20 misure single-shot.
- Avvio a freddo: 10 JVM nuove per scenario e implementazione, zero warmup, una misura single-shot per JVM. Uno scenario include le prime chiamate a entrambi i bean intercettati, per includere creazione di handle/lambda e catene.
- Profilazione: GC per B/op e async-profiler; numeri di throughput del profiling separati dal confronto senza profiler.

## Profilazione del candidato finale

Su methodIntercepted, `gc.alloc.rate.norm` è **0,0008 ± 0,0221 B/op**, cioè sostanzialmente zero nel benchmark monomorfico misurato. Proxy-3 aveva circa 64 B/op nel precedente profilo; Weld 7.0.0 nel nuovo run ha circa 400 B/op. L'inlining reso possibile dalle lambda è coerente con l'eliminazione del contesto da parte dell'escape analysis; non è una garanzia per ogni interceptor.

Il throughput con profiler GC è 32,71 ops/µs sul metodo intercettato, 61,49 sul metodo non intercettato e 199,75 su applicationScopedInterceptionUsed. Non sostituisce il confronto senza profiler.

Campionamento CPU, percentuali self approssimative:

- metodo intercettato: MethodInvoker.invoke 17,7%, getStack 12,9%, Stack.push 7,6%, controllo argomenti 7,1%; le lambda compaiono nel profilo;
- metodo non intercettato: push 18,2%, getStack 16,7%, activeStack 16%, ThreadLocalMap.getEntry 12,1%; resta il costo dello stack/lookup;
- applicationScopedInterceptionUsed: activeStack 36,3%, client proxy 26%; invariato l'obiettivo di questa serie.

Le metriche GC complete sono in `weld-proxy-4-profile-results.json` e i flamegraph nell'artifact `profiles` del run.

## Risultato finale del benchmark

| miglioramento | proxy-3 | proxy-4 | differenza |
|---|---:|---:|---:|
| Weld 7, methodIntercepted, 1 thread | 18,18 ± 0,35 | **33,63 ± 0,31** | **+85%** |
| Weld 7, classIntercepted, 1 thread | 18,65 ± 0,44 | **33,60 ± 0,07** | **+80%** |
| Weld 6, methodIntercepted, 1 thread | 18,46 ± 0,64 | **33,19 ± 0,68** | **+80%** |
| Weld 6, classIntercepted, 1 thread | 18,65 ± 0,29 | **32,72 ± 0,26** | **+75%** |
| Weld 7, methodIntercepted, 4 thread | 21,07 ± 6,54 | **101,73 ± 14,42** | +383%* |
| Weld 6, methodIntercepted, 4 thread | 27,25 ± 18,19 | **55,60 ± 27,00** | +104%* |

Ops/µs, JMH 99,9% CI. *Le misure a quattro thread hanno intervalli molto ampi: i guadagni a un thread sono il risultato più solido. Gli altri scenari singolo-thread restano nei limiti del rumore; in particolare methodNotIntercepted è stabile. Dati completi nell'artifact del run 37281326000.

| Avvio a freddo, intercettato | proxy-3 | proxy-4 | differenza |
|---|---:|---:|---:|
| Weld 7, ms/container | 686,49 ± 34,12 | 688,85 ± 15,61 | +0,3% |
| Weld 6, ms/container | 679,43 ± 19,46 | 691,57 ± 16,62 | +1,8% |

Nessuna regressione distinguibile dal rumore in dieci fork/JVM nuove. I JSON sono in `weld-proxy-4-cold-start-results.json`.

## Pull request upstream aperte

- [Weld 7, PR #3545](https://github.com/weld/core/pull/3545), target `main`.
- [Backport Weld 6, PR #3546](https://github.com/weld/core/pull/3546), target `6.0`.

Poiché le serie client-proxy/proxy-2/proxy-3 non erano ancora upstream, ciascuna PR contiene la catena necessaria, senza i workflow `[fork-only]`. Entrambe riportano le tabelle dei guadagni e i link alle CI.

## Limiti

I benchmark usano poche classi e call site prevalentemente monomorfici. Non dimostrano guadagni identici su molteplici tipi di interceptor, metodi con firme differenti o applicazioni reali. LambdaMetafactory dipende dagli accessi di modulo/lookup e ha un costo alla prima invocazione; dove non applicabile rimane il fallback. L'allocazione osservata dipende dall'escape analysis del JIT.

I test mirati/build usano JDK 17, i test completi/TCK JDK 21. JDK 25 non eseguita. I commit fork-only della CI sono esclusi dalle PR. I maintainer possono ora valutare la catena completa; JDK 25 non è inclusa nella matrice eseguita.
