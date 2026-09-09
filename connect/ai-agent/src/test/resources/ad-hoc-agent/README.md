# Ad-hoc Agent Test Suite (CIB7-1944)

14 deployable BPMN processes for manual and end-to-end runs of the agentic ad-hoc
sub process. They are **not** JUnit tests: each one needs a real distribution and
a real language model. What is automated is only that they stay valid —
`AdHocAgentSuiteDeploymentTest` deploys every file, checks the children each scope
offers and the result variables it derives, and asserts that starting an instance
leaves the agent waiting as a job. It never calls a model.

## Gemeinsame Konfiguration

Jede Datei benutzt dieselbe Grundform:

```xml
<adHocSubProcess id="adHoc">
  <extensionElements><camunda:properties>
    <camunda:property name="explicitCompletionOnly" value="true" />
    <camunda:property name="adHocDriverActivity"    value="agent" />
    <camunda:property name="activeActivityIds"      value="agent" />
  </camunda:properties></extensionElements>

  <serviceTask id="agent" name="AI Agent" camunda:asyncBefore="true">
    ... camunda:connector cibseven-ai-agent, toolClasses = AdHocSubProcessTool
  </serviceTask>
  ...
</adHocSubProcess>
```

Zwischen den Kindaktivitäten gibt es **keine Sequenzflüsse**. Die Reihenfolge kommt
aus den Werkzeugaufrufen des Modells, nicht aus dem Diagramm.

## Voraussetzungen

| | |
|---|---|
| Connect-Plugin | `cibseven-engine-plugin-connect` muss in der Engine registriert sein, sonst wird `camunda:connector` nicht geparst. Eine Distribution hat es. |
| History-Level | `full`. Nur dort trägt jede Variablenänderung ihre Aktivitätsinstanz, und nur dann sieht der Agent nach einer Wartephase, was das Kind geschrieben hat. |
| LLM-Zugang | `baseUrl`, `model` und `apiKey` sind in den Dateien **absichtlich nicht gesetzt** — sie kommen aus der Connector-Konfiguration der Umgebung. Eine Datei, die sie hart verdrahtet, läuft nirgends sonst. |
| Job-Executor | muss aktiv sein. Der Agent ist `asyncBefore`, jede Runde ist ein Job. |

`memoryId` ist ebenfalls absichtlich nicht gesetzt: innerhalb eines Ad-hoc-Bereichs
leitet der Connector sie aus der Bereichs-Execution ab (`adhoc-<executionId>`), also
stabil über Runden und über einen Neustart hinweg. Wer sie setzt, umgeht genau den
Mechanismus, den Fall 10 prüfen soll.

## Die Fälle

| Datei | Prüft | Priorität |
|---|---|---|
| `01-sync-simple.bpmn` | eine synchrone Aktivität, Ergebnis im selben Zug | P0 |
| `02-sync-multi-turn.bpmn` | zwei synchrone Aktivitäten | P1 |
| `03-async-user-task.bpmn` | User Task parkt den Bereich, Treiber wird danach reaktiviert | P0 |
| `04-async-rejection.bpmn` | Agent wählt nach einer Ablehnung einen anderen Weg | P1 |
| `05-mixed-sync-async.bpmn` | sync → warten → sync → beenden | P0 |
| `06-multiple-sync.bpmn` | zwei Aktivierungen in einem Zug | P1 |
| `07-parallel-async-fan-in.bpmn` | zwei parallele User Tasks, **eine** weitere Runde | P1 |
| `08-agent-completion.bpmn` | nur `completeScope()` beendet den Bereich | P0 |
| `09-turn-limit.bpmn` | Rundenobergrenze greift | P2 |
| `10-restart-while-waiting.bpmn` | Zustand übersteht einen Neustart | P0 |
| `11-no-result.bpmn` | Aktivität ohne Ergebnis erfindet keins | P2 |
| `12-multiple-results.bpmn` | alle vier Ableitungswege für Ergebnisvariablen | P1 |
| `13-repeated-result-variable.bpmn` | gleicher Variablenname zweimal | P2 |
| `14-agent-choice.bpmn` | Agent wählt ohne modellierte Reihenfolge | P1 |

## Hinweise zu einzelnen Fällen

**02** — beide Kinder sind synchron und enden innerhalb ihres Aktivierungsaufrufs.
Der Agent erledigt das daher in **einem** Treiberzug, nicht in drei. Drei Züge
entstehen nur, wenn er den Zug dazwischen bewusst beendet — ein synchrones Kind
gibt ihm keine weitere Runde, weil ein Treiber durch sein eigenes Ende nicht
reaktiviert wird. Die Fassung im Testkonzept (Turn 1/2/3) beschreibt insofern eine
Möglichkeit, keine Zwangsfolge.

**07** — um die Zusammenführung zu sehen, **beide** User Tasks abschließen, bevor
der Treiberjob läuft. Der erste Abschluss plant den Job ein; der zweite darf keinen
zweiten einplanen. Läuft der Job dagegen zwischen den beiden Abschlüssen, sind zwei
Runden korrekt und kein Fehler.

**09** — `adHocMaxTurns` steht hier auf **3**, nicht auf 10 wie im Testkonzept. Die
Vorgabe ist 10; drei macht die Grenze nach drei Modellaufrufen sichtbar statt nach
zehn. Bei Erreichen wirft `startActivity`, LangChain4j macht daraus ein
Werkzeugergebnis mit Fehlermarkierung, und das Modell sieht die Meldung.

> **Abweichung vom Konzept:** Ein `stopReason = MAX_TURNS_REACHED` gibt es **nicht**.
> Es existiert keine solche Variable und kein Stop-Zustand. Erwartbar ist allein die
> Ablehnung samt Meldung; der Bereich bleibt danach geparkt, bis jemand
> `completeScope` aufruft oder die Instanz beendet. Wer einen auswertbaren Stopgrund
> braucht, muss ihn als eigene Anforderung stellen.

**10** — strukturell dieselbe Datei wie 03; der Test ist betrieblich. Ablauf:
Instanz starten, Treiberjob laufen lassen, User Task offen stehen lassen, Engine
neu starten, dann den Task abschließen und prüfen, dass der Agent mit unverändertem
`turn`-Zähler und unveränderter Memory-Id fortfährt.

**13** — dokumentiert das aktuelle Verhalten, statt eines zu fordern: beide Kinder
schreiben `result`, das zweite überschreibt das erste. Es gibt keine Aggregation.
Eine Aktivität, die mehrfach laufen soll, sollte in eine Collection schreiben.

**14** — zwei Läufe derselben Datei dürfen sich unterscheiden. Das ist der Punkt.

## Was diese Dateien nicht prüfen

Ob die Werkzeugbeschreibungen ein Modell zu einer **sinnvollen** Reihenfolge
bringen. Das entscheidet das Modell, und dafür gibt es hier keine Zusicherung —
nur die Beobachtung im Lauf.
