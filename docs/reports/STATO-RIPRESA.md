# Stato del lavoro Weld vs OpenWebBeans (salvato il 9 ottobre 2026)

Punto di ripresa per continuare il lavoro. I dettagli sono nei report `REPORT-weld-perf.md` (giro 1), `-2.md`, `-3.md` e `-4.md`.

## Obiettivo

Portare in Weld le tecniche che rendono OpenWebBeans più veloce sulle invocazioni dei proxy CDI, sul modello di quanto BalusC ha fatto per Mojarra con le feature di MyFaces. Le modifiche devono:
- non cambiare la semantica;
- passare il CDI TCK;
- avere un backport su Weld 6.

## Regole concordate con l'utente

- Build, test, TCK, benchmark e profilazione girano **solo in GitHub Actions** sui fork `AngeloRubens/*`, mai sul PC locale (niente JDK o Maven locali).
- Prima di qualsiasi azione upstream (PR, issue, ticket Jira) serve la conferma dell'utente. Le PR attuali le ha aperte l'utente con Codex.

## PR upstream aperte

| PR | target | branch del fork | head |
|---|---|---|---|
| https://github.com/weld/core/pull/3545 | `main` (Weld 7) | `pr/proxy4-main` | 38f94f3 |
| https://github.com/weld/core/pull/3546 | `6.0` (Weld 6) | `pr/proxy4-6.0` | 9d8a605 |

Ogni PR contiene una catena di 17 commit (client-proxy + proxy-2 + proxy-3 + proxy-4), senza i commit `[fork-only]`.

**Non include la request cache** (`perf/request-cache`, commit "Enable RequestScopedCache for the unbound request context" e "Flush RequestScopedCache…"). È ancora da proporre come PR separata.

I commit sul fork sono stati riscritti con autore "Angelo Rubens" e senza la riga Co-Authored-By. Il contenuto è identico a quello dei branch `perf/*`.

**Commento del maintainer** (manovotn, 5 ottobre 2026, sulla PR 3545):
- farà la review più avanti, quando avrà tempo;
- suggerisce di misurare con https://github.com/weld/weld-core-benchmarks, che contiene già test JMH per interceptor e decorator.

**Prossimo passo naturale:** far girare `weld-core-benchmarks` in CI su Weld 7.0.0 e sui branch PR, e postare i numeri sulla PR, dopo conferma dell'utente.

## Repository e branch

**`/home/lop/cdi/weld-core`.** Remote `origin` = fork AngeloRubens/core, `upstream` = weld/core. Tutti i branch `perf/*` sono pushati sul fork.

| giro | Weld 7 | Weld 6 | contenuto |
|---|---|---|---|
| 1 | `perf/client-proxy` | `perf/client-proxy-6.0` | niente stack/ThreadLocal.remove per chiamata, niente Container.isSet |
| 1 | `perf/request-cache` | `perf/request-cache-6.0` | RequestScopedCache nel contesto request unbound |
| 2 | `perf/proxy-2` | `perf/proxy-2-6.0` | stack per thread riusato, SwitchPoint "nessuna interception", RIBean |
| 3 | `perf/proxy-3` | `perf/proxy-3-6.0` | stack con array + indice |
| 4 | `perf/proxy-4` | `perf/proxy-4-6.0` | MethodHandle/LambdaMetafactory per gli interceptor, cache della catena, zero allocazioni |
| PR | `pr/proxy4-main` | `pr/proxy4-6.0` | catena pulita per upstream |

- Scartati: `perf/exp-no-override` (rompe 3 test di Weld sulla self-invocation) e `perf/proxy-4-lmf` (variante).
- I branch locali `p2-work-f2`, `p4-wip` e `p4-60-wip` sono WIP già contenuti nei branch pushati.

**`/home/lop/cdi/cdi-performance`.** Branch `jakarta-2026`, fork https://github.com/AngeloRubens/cdi-performance (`origin` = struberg, da non usare per i push).
- **Benchmark JMH:** `CdiBenchmark` e `CdiNoInterceptorBenchmark`.
- **Script:** `scripts/bench.sh`, `report.py`, `profile.sh`.
- **Workflow:**
  - push su `jakarta-2026` → run completo;
  - push su `ci/bench-quick` → run incrementale;
  - push su `ci/profile` → profilazione.
  - I ref Weld da misurare sono in `.github/weld-refs`.
- **Bozze, stato e JSON dei risultati:** `docs/`.

## Risultati principali

JMH, 1 thread, ops/µs.

| scenario | OWB 4.1.1 | Weld 7.0.0 | Weld 7 con tutte le patch |
|---|---|---|---|
| @ApplicationScoped (con interceptor in uso) | ~718 | ~20 | ~200 |
| @ApplicationScoped (senza interceptor) | ~718 | ~20 | ~325 |
| @RequestScoped | ~90 | ~10.5 | ~141 con la request cache (più veloce di OWB), che non è nelle PR; senza request cache circa 26 nel giro 1, da rimisurare |
| metodo intercettato | ~10 | ~6.7 | ~33.6 (3.4x più veloce di OWB) |
| metodo non intercettato di un bean intercettato | ~505 | ~9.7 | ~60 |
| fireEvent | ~3 | ~43 | invariato (Weld 14x più veloce di OWB) |

Weld 6 con il backport ha gli stessi guadagni.

## Gap residui e idee aperte

1. **metodo non intercettato** (circa 8x): servono un client proxy che salti `startIfNotEmpty()` per i metodi non intercettati, oppure un cambio della semantica della self-invocation, che spetta ai maintainer. Vedi `docs/weld-self-invocation-redesign.md`.
2. **@ApplicationScoped con interceptor in uso** (circa 3.6x): resta una `ThreadLocal.get()` per chiamata nel client proxy.
3. **CI su JDK 25:** mai eseguita. Il TCK è stato eseguito solo su JDK 21.
4. **Integrare `weld-core-benchmarks`**, come suggerito dal maintainer.
5. **Request cache:** non è nelle PR aperte. Proporla come PR separata (bozza in `docs/weld-pr-request-cache-draft.md`), da rebasare sopra le PR attuali. Cambia la semantica più delle altre modifiche: va discussa con i maintainer.

## Aggiornamento del 9 ottobre 2026 (sera)

**TCK sul codice esatto delle PR:** verde.
- Branch `perf/pr-check-main` e `perf/pr-check-6.0` = head delle PR + workflow della CI del fork.
- Run 37904857772 (Weld 7) e 37904857540 (Weld 6).

**Request cache pronta, stacked sulle PR:**
- Branch `pr/request-cache-main` (d1fa54e) e `pr/request-cache-6.0` (6825d96) sul fork. Autore "Angelo Rubens", senza Co-Authored-By, come i commit delle PR.
- TCK verde: run 37904858396 (Weld 7) e 37904858262 (Weld 6).
- Benchmark: run 37904967425. requestScoped da 22.5 a 142.5 ops/µs (OWB 88).
- Bozza pronta: `cdi-performance/docs/weld-pr-request-cache-draft.md`.
- **Da aprire upstream dopo la conferma dell'utente.**

**Da correggere nel testo della PR #3545:** dice che Matej Novotny è il "creator of OpenWebBeans" e l'autore dello scenario di benchmark. È sbagliato: OWB è un progetto Apache, il benchmark di partenza è `cdi-performance` di Mark Struberg, e manovotn è il maintainer di Weld.
