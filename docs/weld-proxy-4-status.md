# Weld proxy-4: ripresa del lavoro di Claude (5 ottobre 2026)

**Stato: candidato preparato, validazione finale bloccata dalla rete. Non pronto per upstream.**
Il report distingue gli esiti recuperati dagli artifact locali dalle verifiche ancora da eseguire.

## Stato recuperato

Claude aveva completato sei modifiche su Weld 7 e il relativo backport Weld 6:

1. `MethodInvoker`: MethodHandle con fallback alla reflection, conversioni controllate e mantenimento di InvocationTargetException.
2. Percorso rapido per catene già inizializzate, senza ripetere i controlli di accessibilità e di interceptor sul target.
3. Cache della prima catena intercettata per istanza, con fallback alla ConcurrentHashMap.
4. Rimozione dei campi timer e constructor inutilizzati dai contesti around-invoke.
5. Array vuoto condiviso per gli argomenti dei metodi senza parametri.
6. Riuso dello stack durante proceed() sul thread proprietario; lookup normale sugli altri thread.

Il backport **era già completato**, benché l'ultima notifica riportasse ancora la risoluzione dei conflitti.
L'esperimento LambdaMetafactory è separato (`p4-wip`, `perf/proxy-4-lmf`, tag `bench/p4-5`): non promosso senza i risultati del confronto.

## Risultati incrementali recuperati

Fonte: artifact locale del [run 37204192518](https://github.com/AngeloRubens/cdi-performance/actions/runs/37204192518).
Tre fork, quattro misure da un secondo, un thread; throughput ops/µs. Ogni confronto vale solo all'interno di questo runner.

| variante | methodIntercepted | classIntercepted | methodNotIntercepted |
|---|---:|---:|---:|
| proxy-3 | 22,39 ± 0,46 | 22,43 ± 0,59 | 70,10 ± 0,13 |
| soli MethodHandle (p4-1) | 22,56 ± 0,08 | 22,45 ± 0,55 | 69,97 ± 0,18 |
| + controlli in cache (p4-2a) | 23,00 ± 0,30 | 22,86 ± 0,39 | 69,30 ± 0,81 |
| + prima catena in cache (p4-2b) | 26,52 ± 0,25 | 26,30 ± 0,61 | 70,00 ± 0,09 |
| + contesto più piccolo (p4-3a) | 27,03 ± 0,32 | 27,32 ± 0,44 | 69,91 ± 0,34 |
| + array vuoto condiviso (p4-3b) | 28,32 ± 0,47 | 28,43 ± 0,25 | 69,76 ± 0,46 |
| ripetizione p4-3b | 28,12 ± 0,23 | 28,03 ± 0,41 | 67,85 ± 2,87 |

Il miglioramento p4-3b rispetto alla prima misura proxy-3 è circa **26,5%** su methodIntercepted.
I soli MethodHandle non mostrano un vantaggio significativo. Il secondo confronto (run 37205424057) deve stabilire se conservarli e valutare LambdaMetafactory e riuso dello stack.
I dati di questo primo run NON misurano la correzione di accessibilità aggiunta durante la ripresa.
La copia strutturata delle metriche, con hash SHA-256 dei JSON originali, è in `docs/weld-proxy-4-incremental-results.json`.

## CI recuperata dai log della sessione

- Weld 6, [37204455985](https://github.com/AngeloRubens/core/actions/runs/37204455985), commit `deac986`: tutti gli otto job completati positivamente, inclusi TCK, relaxed mode e test in-container.
- Weld 7, [37204452905](https://github.com/AngeloRubens/core/actions/runs/37204452905), commit `861e2b0`: otto job verdi nel log; relaxed mode ancora in corso all'ultima lettura. Esito finale non recuperato.
- Benchmark incrementale 2: [37205424057](https://github.com/AngeloRubens/cdi-performance/actions/runs/37205424057), risultato non recuperato.
- Esperimento LambdaMetafactory: push registrato, esito CI non recuperato.

Questi esiti precedono le nuove correzioni; non costituiscono validazione degli HEAD attuali.

## Correzioni della ripresa

**Accessibilità:** la cache usa Method.equals(), che non distingue il flag setAccessible(). Un handle creato per un Method con accesso forzato poteva essere riutilizzato per un Method uguale senza quel permesso, oppure dopo setAccessible(false). Ora, se l'handle era stato creato con controlli soppressi, il percorso rapido richiede che il Method passato abbia ancora quell'override; altrimenti usa la reflection e i suoi controlli.

Aggiunti due test per accesso revocato e copie di Method, coprendo invocazioni senza argomenti, con argomento singolo e con array. Aggiunti due test per stack riusato sul thread proprietario e passaggio a un altro thread. Corretto il commento della cache della prima catena: inizializzazioni concorrenti possono scrivere catene diverse, sempre valide; non è una scrittura garantita una sola volta.

- Weld 7: `perf/proxy-4`, commit `5c6a9f4cc8b8bbdfa6d893d320de89ab850725aa`.
- Weld 6: `perf/proxy-4-6.0`, commit `0ab95263c14c4851a476b1be8b817cf59458cbfc`.
- Entrambi sono commit locali, non ancora pubblicati.

## Benchmark completo preparato

In cdi-performance, branch `jakarta-2026`:

- refs fissate ai commit di proxy-3 e proxy-4, Weld 7 e 6;
- versioni rilasciate Weld 7.0.0, Weld 6.0.4 e OWB 4.1.1 mantenute dal workflow;
- supporto al fetch di commit esatti in build-weld.sh;
- misure a quattro thread: 3 fork, 5 warmup da 2 s, 10 misure da 2 s;
- boot/shutdown e benchmark completi a un thread restano inclusi;
- colonne proxy-4/proxy-3 e proxy-4/versione rilasciata nel report;
- errori di build o benchmark delle varianti propagate allo stato del job, continuando a raccogliere gli altri risultati.

## Verifiche e lavoro restante

Eseguiti i controlli locali di whitespace e sintassi shell. Test Java, build Weld, TCK e benchmark finali restano da eseguire in CI, coerentemente con il flusso precedente.

La sessione non risolve api.github.com; gh e curl falliscono. Il profilo dell'ambiente vieta l'escalation della sandbox, indipendentemente dal consenso espresso nella chat.

Per chiudere:

1. Ripristinare l'accesso di rete e recuperare CI Weld 7, confronto 37205424057 e CI LambdaMetafactory.
2. Scegliere la variante sulla base dei dati: conservare i MethodHandle solo se giustificati, senza promuovere automaticamente l'esperimento LambdaMetafactory.
3. Pubblicare i due branch corretti sul fork AngeloRubens/core e verificarne test/TCK sugli SHA esatti.
4. Pubblicare jakarta-2026 sul fork AngeloRubens/cdi-performance per avviare il confronto completo. Se cambia il candidato, aggiornare prima gli SHA in .github/weld-refs.
5. Verificare tempi di boot, quattro thread e regressioni negli altri scenari; aggiornare questo report e la bozza PR con i risultati effettivi.

Nessun ticket o PR upstream è stato aperto.
