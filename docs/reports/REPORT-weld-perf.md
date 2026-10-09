# Report: performance dei client proxy di Weld (ottobre 2026)

Lavoro svolto in autonomia. Build, test, profilazioni e benchmark sono stati eseguiti **solo su GitHub Actions**
(fork `AngeloRubens/*`). Nessuna PR, issue o ticket è stato aperto upstream: i testi sono bozze in
`/home/lop/cdi/cdi-performance/docs/`.

> All'inizio avevo scaricato JDK 21 e Maven in `/home/lop/cdi/tools` e fatto una build e un benchmark locali di prova.
> Dopo l'istruzione "solo CI" ho cancellato `/home/lop/cdi/tools` e le directory `target/`. I numeri locali non sono
> usati da nessuna parte. `~/.m2` (1,3 GB) esisteva già e l'ho lasciata; contiene anche gli artefatti di quella build.

## 1. Link

| cosa | link |
|---|---|
| Fork Weld 7.x | https://github.com/AngeloRubens/core/tree/perf/client-proxy |
| Fork Weld 7.x + request cache | https://github.com/AngeloRubens/core/tree/perf/request-cache |
| Backport 6.0 | https://github.com/AngeloRubens/core/tree/perf/client-proxy-6.0 , https://github.com/AngeloRubens/core/tree/perf/request-cache-6.0 |
| cdi-performance | https://github.com/AngeloRubens/cdi-performance/tree/jakarta-2026 |
| Profilazione Weld 7.0.0 vs OWB | https://github.com/AngeloRubens/cdi-performance/actions/runs/37111893058 |
| Profilazione patched | https://github.com/AngeloRubens/cdi-performance/actions/runs/37112408307 |
| Benchmark incrementale (un commit per colonna) | https://github.com/AngeloRubens/cdi-performance/actions/runs/37112321830 |
| **Benchmark completo** | https://github.com/AngeloRubens/cdi-performance/actions/runs/37113133484 |
| Benchmark rapido Weld 6 + request cache | https://github.com/AngeloRubens/cdi-performance/actions/runs/37113771427 |
| Weld CI+TCK perf/client-proxy | https://github.com/AngeloRubens/core/actions/runs/37113100181 |
| Weld CI+TCK perf/request-cache | https://github.com/AngeloRubens/core/actions/runs/37113016403 |
| Weld CI+TCK perf/client-proxy-6.0 | https://github.com/AngeloRubens/core/actions/runs/37113033900 |
| Weld CI+TCK perf/request-cache-6.0 | https://github.com/AngeloRubens/core/actions/runs/37113764340 |

**Base dei branch:**
- `perf/client-proxy` parte da **`main` di weld/core** (7.0.1-SNAPSHOT), **non dal tag 7.0.0.Final**. `main` ha 3 commit
  in più rispetto al tag: "prepare for next development iteration", una correzione al sigtest e "Fix synthetic bean
  priority collisions (#3541)".
- `perf/client-proxy-6.0` parte dal branch **`6.0`** (6.0.5-SNAPSHOT, 30 commit dopo 6.0.4.Final, quasi tutti bump di
  dipendenze).
- Le baseline dei benchmark sono le release di Maven Central, quindi quei commit upstream finiscono nella colonna
  "patched". Non ho misurato uno snapshot non patchato, per cui non è verificato che non abbiano effetto. È però
  improbabile: non toccano il percorso delle invocazioni, e nel benchmark incrementale ogni guadagno compare
  esattamente con il commit che lo introduce.

## 2. Cosa ha mostrato la profilazione (Weld 7.0.0.Final)

Strumenti: async-profiler 4.5 (CPU, flamegraph e stack collapsed) e JMH `-prof gc`, JDK 21, run 37111893058. I
flamegraph sono nell'artifact `profiles`, il riepilogo per frame è fatto con `scripts/stacks.py`.

| benchmark | ops/µs | alloc/chiamata | dove va il tempo |
|---|---|---|---|
| applicationScoped | 20,3 | **168 B** | **78 %** in `InterceptionDecorationContext.startIfNotEmpty()`. `getStack()` crea `Stack` + `ArrayDeque` e fa `ThreadLocal.set`; poi `removeIfEmpty()` fa subito `ThreadLocal.remove()` (circa 40 %: `Reference.clear0` nativo ed expunge). Il resto si divide tra `Container.isSet` (lookup in CHM) e il dispatch della strategy. |
| requestScoped | 10,6 | 168 B | circa 48 % nello stesso `startIfNotEmpty`; circa 43 % in `ContextualInstance.getIfExists`. Con `RequestContextController` (contesto unbound) la `RequestScopedCache` non è attiva, quindi ogni chiamata passa da `BeanManagerImpl.getContext`, `isActive` e dalla `HashMap` del bean store. |
| methodIntercepted | 7,2 | 400 B | stesso set/remove del ThreadLocal, sia nel client proxy sia nella subclass intercettata (`startIfNotOnTop`); il resto è la catena degli interceptor (reflection) |
| OWB 4.1.1 (confronto) | 717 / 92 / 10 | 0 / 0 / 368 B | il proxy di OWB tiene in cache l'istanza `@ApplicationScoped`; per `@RequestScoped` fa un lookup in `HashMap` a ogni chiamata |

**Ipotesi iniziale: confermata solo in parte.** Il collo di bottiglia è effettivamente il contesto di
interception/decoration. Però il costo non viene dalla logica dello stack: viene dalla creazione e distruzione di una
entry ThreadLocal a ogni chiamata fatta fuori da una request. `Container.isSet` e la strategy valgono pochi punti
percentuali. Il controllo `return this` e il volatile read sono trascurabili.

Dopo le patch (run 37112408307, che conteneva anche la prima versione della request cache):

| benchmark | ops/µs | alloc/chiamata |
|---|---|---|
| applicationScoped | 219 | 0 B |
| requestScoped | 122 | 0 B |
| methodIntercepted | 15,9 | 152 B |

Su applicationScoped rimane circa il 40 % nella `ThreadLocal.get()` di `startIfNotEmpty`. Il resto è il corpo del
proxy più `ProxyMethodHandler`, `ContextBeanInstance`, `ContextualInstance` e la strategy.

## 3. Modifiche e numeri

### Incrementale (stesso job, JMH 1 thread, ops/µs ± errore al 99,9 %, run 37112321830, quick mode con 2 fork × 4 iterazioni)

| benchmark | 7.0.0.Final | +a | +a2 | +c | +d* | OWB 4.1.1 |
|---|---|---|---|---|---|---|
| applicationScoped | 20,37 ± 0,63 | 138,29 ± 2,15 | 138,72 ± 0,73 | **187,33 ± 0,58** | 186,99 ± 0,68 | 717,12 ± 3,91 |
| requestScoped | 10,04 ± 0,81 | 21,45 ± 0,30 | 20,43 ± 1,45 | 21,82 ± 1,02 | **106,53 ± 0,57** | 92,00 ± 0,17 |
| classIntercepted | 6,98 ± 0,26 | 9,98 ± 0,16 | 14,78 ± 0,03 | **15,77 ± 0,13** | 15,62 ± 0,36 | 9,87 ± 0,49 |
| methodIntercepted | 7,05 ± 0,11 | 9,87 ± 0,14 | 14,84 ± 0,10 | **15,55 ± 0,24** | 15,68 ± 0,74 | 9,35 ± 0,11 |
| methodNotIntercepted | 9,49 ± 0,05 | 16,68 ± 0,10 | 35,82 ± 0,32 | **38,39 ± 0,25** | 37,99 ± 0,79 | 504,44 ± 1,14 |
| fireEvent (non toccato) | 43,68 ± 0,37 | 43,47 ± 0,37 | 42,99 ± 1,47 | 43,83 ± 1,46 | 42,26 ± 2,67 | 3,00 ± 0,01 |

\* La colonna +d è la prima versione della request cache, senza il flush in `AbstractBoundContext` (vedi d). La
versione finale è misurata nel run completo.

I valori assoluti cambiano da runner a runner: si possono confrontare solo colonne dello stesso run.

**(a) `startIfNotEmpty()` non crea più lo stack.** Se sul thread non c'è uno stack, restituisce `null`. È
semanticamente identico, perché il codice vecchio non lasciava niente. Effetto: applicationScoped **x6,8**, gli altri
benchmark x1,4–x2.

Rispetto alla proposta originale ("saltare il contesto per i bean senza interceptor") **non** ho generato proxy
diversi per quei bean. Il push di `NULL_INSTANCE` conta anche per bean senza interceptor. Caso concreto: un bean A,
`@Dependent` e intercettato, chiama B tramite proxy, e B richiama A con un riferimento diretto. Senza il push, in cima
allo stack resta l'handler di A, e la richiamata verrebbe trattata come self-invocation, quindi non intercettata. La
modifica (a) ottiene quasi tutto il guadagno senza cambiare il bytecode.

**(a2) `ThreadLocal.set(null)` al posto di `remove()`, e `ArrayDeque(4)`.** Le subclass intercettate, fuori da una
request, creano e rimuovono lo stack a ogni chiamata. `remove()` azzera la WeakReference della entry e costringe la
`set()` successiva a riallocarla. Un valore `null` non trattiene classi Weld, quindi non ci sono leak di
classloader; nella `ThreadLocalMap` dei thread interessati resta solo una entry vuota. Effetto: intercepted **x1,5**,
methodNotIntercepted **x2,1**.

**(c) Niente lookup `Container.isSet()` a ogni chiamata.** `ContextBeanInstance` conserva il `Container`; un nuovo
flag volatile `cleanedUp`, impostato come prima istruzione di `cleanup()`, decide se fare il lookup nel registry. Il
lookup si fa solo dopo lo shutdown, quindi l'eccezione "not valid after shutdown" resta invariata. Effetto:
applicationScoped **+35 %**.

**(b) Istanza `@ApplicationScoped` in cache nel proxy: NON fatta.** Nel profilo che resta dopo (a)+(c) pesa circa il
20 %, contro il 40 % della ThreadLocal.get. Per farla bene servirebbe l'invalidazione su `AlterableContext.destroy()`
e allo shutdown, oltre alla verifica con il TCK, e non c'era tempo. È il primo dei prossimi passi.

**(d) RequestScoped: `RequestScopedCache` nel contesto request unbound.** Branch separato `perf/request-cache`,
costruito sopra client-proxy.
1. `RequestContextImpl` chiama begin/end della cache come `BoundRequestContextImpl`, con end prima della distruzione
   delle istanze.
2. `AbstractBoundContext.deactivate()` invalida la cache. Senza questo, `NaiveClusterTest.testMultipleDependentObjectsSessionReplication`
   falliva (run https://github.com/AngeloRubens/core/actions/runs/37112296518). Il test sostituisce il bean store
   della sessione mentre la cache è attiva.

Effetto: requestScoped **x5** rispetto a client-proxy, e sopra OWB. La semantica cambia di più: mentre il contesto
unbound è attivo, le istanze request, session e conversation restano in cache in un ThreadLocal. Per questo è una
PR separata.

Catena degli interceptor rispetto a OWB: dopo (a2) Weld è già più veloce di OWB sui metodi intercettati. Quello che
resta è reflection (`Method.invoke`). Qui non ho cambiato niente.

### Run completo (37113133484): 2 fork × 5 iterazioni da 2 s, 1 e 4 thread, boot, legacy, tutto sullo stesso runner

| 1 thread | OWB 4.1.1 | Weld 6.0.4 | Weld 6 patched | Weld 7.0.0 | Weld 7 patched | Weld 7 + req. cache |
|---|---|---|---|---|---|---|
| applicationScoped | 979,3 ± 8,5 | 25,04 ± 0,12 | 244,8 ± 1,3 (x9,8) | 24,96 ± 0,21 | **244,0 ± 2,1** (x9,8) | 244,4 ± 1,4 |
| requestScoped | 99,8 ± 1,6 | 12,52 ± 0,10 | 22,5 ± 2,7 (x1,8) | 12,94 ± 0,11 | 26,24 ± 0,05 (x2,0) | **132,7 ± 0,2** (x10,3) |
| classIntercepted | 12,81 ± 0,02 | 8,79 ± 0,17 | 19,34 ± 0,19 (x2,2) | 8,84 ± 0,08 | **19,24 ± 0,20** (x2,2) | 17,5 ± 2,9 |
| methodIntercepted | 12,95 ± 0,21 | 8,84 ± 0,08 | 19,41 ± 0,22 (x2,2) | 8,86 ± 0,05 | **19,46 ± 0,27** (x2,2) | 19,47 ± 0,55 |
| methodNotIntercepted | 696,5 ± 4,5 | 12,33 ± 0,11 | 47,8 ± 5,0 (x3,9) | 12,44 ± 0,11 | **50,90 ± 0,65** (x4,1) | 50,50 ± 0,20 |
| fireEvent | 4,41 ± 0,04 | 55,1 ± 1,7 | 51,3 ± 7,4 | 54,97 ± 1,59 | 51,8 ± 3,3 | 55,4 ± 2,0 |
| boot+shutdown ms | 34,5 ± 2,6 | 34,7 ± 2,5 | 32,5 ± 2,4 | 31,7 ± 2,0 | 32,6 ± 2,0 | 32,9 ± 2,5 |

A 4 thread:

| benchmark | 7.0.0 | patched | + req. cache | OWB |
|---|---|---|---|---|
| applicationScoped | 39,3 ± 17,9 | 523,6 ± 52,8 | | |
| requestScoped | 26,2 ± 4,2 | | 272,6 ± 2,8 | 270,5 |

A 4 thread gli errori sono grandi: il runner ha 4 vCPU condivise.

Test legacy (ms, più basso è meglio):

| test | Weld 7.0.0 | patched | + request cache | OWB |
|---|---|---|---|---|
| `@ApplicationScoped` | 2158 | 209 | 208 | 12 |
| `@RequestScoped` | 4529 | 2794 | 461 | 525 |
| intercepted | 6010 | 2688 | | 3720 |
| non-intercepted | 4410 | 1184 | | 88 |

**fireEvent:** il -6 % delle versioni patched è dentro l'errore, e in altri run il segno si inverte (+5 % nel run
37113771427). Le patch non toccano il codice degli eventi: lo considero rumore.

**Backport 6.0:** cherry-pick senza conflitti, stessi guadagni. Con la request cache (run 37113771427)
requestScoped passa da 10,48 ± 0,35 a 106,51 ± 0,43.

## 4. Test e TCK

`fork-ci.yml` è una copia di `ci-actions.yml` attivata dai push su `perf/**`, con il solo JDK 21 (gli esempi girano su
JDK 17 come upstream). Sta nel commit `[fork-only]` in cima a ogni branch e va **tolto** prima di proporre le PR.
Tutto verde, 0 failure e 0 error:

| job | 7.x client-proxy | 7.x request-cache | 6.0 client-proxy | 6.0 request-cache |
|---|---|---|---|---|
| test senza container | 3267 (16 skip) | 3267 | 2807 (8 skip) | 2807 |
| in-container (WildFly patchato) | 2086 (25 skip) | 2086 | 1811 (16 skip) | 1811 |
| **CDI TCK (WildFly)** | **2089** | **2089** | **1903** | **1903** |
| CDI TCK SE | 36 | 36 | 34 | 34 |
| relaxed mode | 5342 | 5342 | 4697 | 4697 |
| signature test | ok | ok | assente nella CI 6.0 | assente |
| SE-Servlet coop, esempi | ok | ok | ok | ok |

**Non eseguiti:**
- JDK 17 e 25 della matrice upstream.
- Test unitari nuovi per le modifiche: non ne ho scritti. La copertura viene dalle suite esistenti, tra cui
  `CachedInterceptionDecorationContextTest` e i test di self-invocation.

## 5. Rischi e caveat

- (a), (a2), (c): rischio basso, nessun cambiamento osservabile.
  - (a2) lascia una entry con valore `null` nella `ThreadLocalMap`, senza leak. Un maintainer potrebbe comunque
    preferire `remove()`, e in quel caso il guadagno di (a2) si perde.
  - (c): dopo `readResolve` il container è quello corrente; se lo stesso id viene registrato di nuovo si torna al
    lookup.
- (d) è la modifica semanticamente più delicata (cache ThreadLocal nel contesto unbound). Passa TCK e test, ma va
  discussa con i maintainer.
- I runner GitHub sono condivisi: confrontare solo colonne dello stesso run. Tra un run e l'altro gli assoluti variano
  fino al 35 % (OWB applicationScoped 717 in un run, 979 in un altro).

## 6. Prossimi passi

1. Cache dell'istanza `@ApplicationScoped` nel proxy o in `ContextBeanInstance`, invalidata da
   `ApplicationScopedContextualInstanceStrategy.destroy()` e allo shutdown. Il gap residuo con OWB è circa x4.
2. Evitare `ThreadLocal.get()` nei proxy dei bean senza interceptor o decorator. Va però risolto prima il caso limite
   descritto in (a), per esempio con un contatore globale di stack attivi.
3. Sostituire la reflection nella catena degli interceptor con `MethodHandle` o bytecode.
4. Far girare la CI anche su JDK 17 e 25. Poi, dopo la tua revisione, aprire il ticket WELD e le PR.

## 7. Bozze upstream (non inviate)

In `/home/lop/cdi/cdi-performance/docs/` (anche su GitHub, branch `jakarta-2026`):
- `weld-jira-draft.md`: ticket WELD
- `weld-pr-7.0-draft.md`: PR per `main` con (a), (a2) e (c)
- `weld-pr-request-cache-draft.md`: PR separata per (d)
- `weld-pr-6.0-draft.md`: backport sul branch `6.0`

Prima dell'invio: inserire il numero WELD, togliere il commit `[fork-only]`, decidere se proporre (d).

## 8. Modifiche a cdi-performance (`jakarta-2026`)

- `profile.yml` e `scripts/profile.sh`: JMH gc e async-profiler; riepilogo con `scripts/stacks.py`.
- `scripts/build-weld.sh`, `scripts/bench-patched.sh` e `.github/weld-refs` (coppie `label=branch`): la CI costruisce
  i branch del fork Weld e li misura nello stesso job di OWB e Weld 6/7.
- `bench.sh`: opzioni `LEGACY`, `BOOT`, `THREADS`, `BENCH`. Quick mode con un push su `ci/bench-quick`.
- `report.py`: il "winner" ora si confronta con il migliore di un'**altra** implementazione (prefisso owb/weld), e c'è
  una colonna di speed-up per ogni coppia patched/baseline.
- `readme.md`: sezione "2026 results".
- `workflow_dispatch` funziona solo sul default branch, che è ancora `master`. Il cambio di default branch del fork è
  stato bloccato dal sistema di permessi e non l'ho forzato. Per questo i workflow partono con i push su `ci/profile` e
  `ci/bench-quick`, mentre un push su `jakarta-2026` lancia il run completo. Se vuoi usare `workflow_dispatch`, imposta
  `jakarta-2026` come default branch nelle impostazioni del fork.
