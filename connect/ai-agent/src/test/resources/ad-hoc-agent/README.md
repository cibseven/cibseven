# Ad-hoc Agent Test Suite (CIB7-1944)

15 deployable BPMN processes for manual and end-to-end runs of the agentic ad-hoc
sub process. They are **not** JUnit tests: each one needs a real distribution and
a real language model. What is automated is only that they stay valid —
`AdHocAgentSuiteDeploymentTest` deploys every file, checks the children each scope
offers and the result variables it derives, and asserts that starting an instance
leaves the first turn waiting as a job. It never calls a model.

## Gemeinsame Konfiguration

Jede Datei benutzt dieselbe Grundform:

```xml
<adHocSubProcess id="adHoc">
  <extensionElements>
    <camunda:connector>
      <camunda:connectorId>cibseven-ai-agent</camunda:connectorId>
      <camunda:inputOutput>
        <camunda:inputParameter name="agentName">Ad-hoc Agent</camunda:inputParameter>
        <camunda:inputParameter name="message">...</camunda:inputParameter>
        <camunda:inputParameter name="instruction">...</camunda:inputParameter>
        <camunda:inputParameter name="instructionMode">append</camunda:inputParameter>
      </camunda:inputOutput>
    </camunda:connector>
  </extensionElements>

  ... die Werkzeuge, als gewöhnliche Kindaktivitäten
</adHocSubProcess>
```

Der Agent ist **der Connector des Rahmens**, kein Kind davon: im Diagramm gibt es kein
Agentenkästchen mehr. Es ist derselbe Connector mit denselben Eingabeparametern wie bei
einem AI-Agent-Service-Task, nur werden sie in jedem Zug neu ausgewertet. Ohne
Ausgabeparameter landet die Antwort in `agentOutput`. Ein Zug ist ein Job auf der
Bereichs-Execution; den ersten legt ein Start-Listener beim Betreten an, jeden weiteren
das Ende einer Kindaktivität.

Zwischen den Kindaktivitäten gibt es **keine Sequenzflüsse**. Die Reihenfolge kommt
aus den Werkzeugaufrufen des Modells, nicht aus dem Diagramm.

## Voraussetzungen

| | |
|---|---|
| Connect-Plugin | `cibseven-engine-plugin-connect` muss in der Engine registriert sein, sonst erkennt niemand den Connector des Agenten auf dem Rahmen: der Bereich wird ein gewöhnlicher Ad-hoc-Bereich, der auf Aktivierung von außen wartet, und kein Agent treibt ihn. Eine Distribution hat es. |
| History-Level | `full`. Nur dort trägt jede Variablenänderung ihre Aktivitätsinstanz, und nur dann sieht der Agent nach einer Wartephase, was das Kind geschrieben hat. |
| LLM-Zugang | Die Eingabeparameter `baseUrl`, `model` und `apiKey` sind in den Dateien **absichtlich nicht gesetzt** — sie kommen aus der Connector-Konfiguration der Umgebung. Eine Datei, die sie hart verdrahtet, läuft nirgends sonst. |
| Job-Executor | muss aktiv sein. Jeder Zug ist ein Job. |

`memoryId` lässt sich hier nicht setzen: ein Zug leitet sie aus der Bereichs-Execution
ab (`adhoc-<executionId>`), also stabil über Runden und über einen Neustart hinweg.
Genau darauf zielt Fall 10.

## Die Fälle

| Datei | Prüft | Priorität |
|---|---|---|
| `01-sync-simple.bpmn` | eine synchrone Aktivität, Ergebnis im selben Zug | P0 |
| `02-sync-multi-turn.bpmn` | zwei synchrone Aktivitäten | P1 |
| `03-async-user-task.bpmn` | User Task parkt den Bereich, danach folgt der nächste Zug | P0 |
| `04-async-rejection.bpmn` | Agent wählt nach einer Ablehnung einen anderen Weg | P1 |
| `05-mixed-sync-async.bpmn` | sync → warten → sync → beenden | P0 |
| `06-multiple-sync.bpmn` | zwei Aktivierungen in einem Zug | P1 |
| `07-parallel-async-fan-in.bpmn` | zwei parallele User Tasks, **eine** weitere Runde | P1 |
| `08-agent-completion.bpmn` | der Agent startet nichts und beendet mit `completeScope()` | P0 |
| `15-async-worker.bpmn` | `asyncBefore`-Kind: `waiting`, Wert erst im Zug danach | P1 |
| `09-turn-limit.bpmn` | Rundenobergrenze greift | P2 |
| `10-restart-while-waiting.bpmn` | Zustand übersteht einen Neustart | P0 |
| `11-no-result.bpmn` | Aktivität ohne Ergebnis erfindet keins | P2 |
| `12-multiple-results.bpmn` | alle vier Ableitungswege für Ergebnisvariablen | P1 |
| `13-repeated-result-variable.bpmn` | gleicher Variablenname zweimal | P2 |
| `14-agent-choice.bpmn` | Agent wählt ohne modellierte Reihenfolge | P1 |

## Hinweise zu einzelnen Fällen

**02** — beide Kinder sind synchron und enden innerhalb ihres Aktivierungsaufrufs.
Der Agent erledigt das daher in **einem** Zug, nicht in drei. Ein Kind, das noch im
selben Zug fertig wird, plant keinen weiteren ein. Die Fassung im Testkonzept
(Turn 1/2/3) beschreibt insofern eine Möglichkeit, keine Zwangsfolge.

**08** — der Bereich bietet eine Aktivität an, die der Agent laut Anweisung gerade
nicht starten soll. Anbieten muss er etwas: ein agentischer Bereich ohne startbares
Kind wird beim Deployment abgelehnt, und einer, der nichts anzubieten hat, wäre auch
kein Fall für einen Agenten.

**07** — um die Zusammenführung zu sehen, **beide** User Tasks abschließen, bevor
der Zugjob läuft. Der erste Abschluss plant den Job ein; der zweite darf keinen
zweiten einplanen. Läuft der Job dagegen zwischen den beiden Abschlüssen, sind zwei
Züge korrekt und kein Fehler.

**09** — `cibseven.agentic.maxTurns` steht hier auf **3**, nicht auf 10 wie im Testkonzept. Die
Vorgabe ist 10; drei macht die Grenze nach drei Zügen sichtbar statt nach zehn. Die
Grenze wird vom Zug-Job geprüft, **bevor** das Modell etwas kostet: ist sie erreicht,
wird eine lesbare Antwort in die Ergebnisvariable geschrieben, statt ein Modell zu
fragen, dessen Schleife offensichtlich nicht konvergiert.

> **Die Grenze stoppt den Agenten, nicht die Arbeit, die er schon angestoßen hat.**
> Läuft noch ein Kind — ein User Task, ein eingereihter Job —, endet der Bereich
> *nicht* sofort. Der Wunsch wird vermerkt, und der Bereich endet, sobald das letzte
> Kind fertig ist. Jeder Weckruf bis dahin kostet kein Modell und schreibt die Antwort
> kein zweites Mal. Denn den Bereich über laufender Arbeit zu beenden hieße, sie
> abzubrechen — dieselbe Regel, für die sich `completeScope` verweigert und auf die
> `endOfTurn` wartet. In diesem Fall 09 sind beide Kinder synchron, also ist beim
> Erreichen der Grenze ohnehin nichts mehr offen und der Bereich endet unmittelbar.

> **Kein Fehler, aber zu beachten:** Schließt niemand einen offenen User Task ab,
> wartet der Bereich über die Grenze hinaus genauso weiter wie nach einem
> abgelehnten `completeScope`. Den Grund kann der Prozess schon in dieser Zeit lesen:
> Der Zug an der Grenze schreibt neben die Antwort in `agentOutput` den Wert
> `turnLimit` nach `agentOutcome` (CIB7-2151).

> **Abweichung vom Konzept:** Ein `stopReason = MAX_TURNS_REACHED` gibt es **nicht**.
> Den Stopgrund trägt `agentOutcome`, mit Ausgabeparametern `${outcome}` (CIB7-2151):
> `turnLimit` hier, `callLimit` oder `totalTurnLimit` an den anderen Grenzen, sonst
> `completed`, `ended` oder `waiting`. Ein Gateway nach dem Bereich verzweigt darauf.
> Mit `cibseven.agentic.onLimit = error` endet der Bereich an einer Grenze stattdessen
> mit einem BPMN-Fehler, dessen Code dieser Wert ist, über ein Boundary Error Event.

**10** — strukturell dieselbe Datei wie 03; der Test ist betrieblich. Ablauf:
Instanz starten, Zugjob laufen lassen, User Task offen stehen lassen, Engine
neu starten, dann den Task abschließen und prüfen, dass der Agent mit unverändertem
`turn`-Zähler und unveränderter Memory-Id fortfährt.

**13** — beide Kinder schreiben `result`. In den **Prozessvariablen** überschreibt das
zweite das erste; im Bericht des Agenten nicht. Die Engine sammelt jede Ausführung ein,
sobald ihr Kind endet — also solange deren Werte noch die aktuellen sind — und
`finishedSinceLastTurn` speist sich daraus. Der Agent sieht beide Ergebnisse, jedes
genau einmal. Wer den Endzustand im Prozess braucht, liest weiterhin `result` und
bekommt den letzten Schreiber.

**14** — zwei Läufe derselben Datei dürfen sich unterscheiden. Das ist der Punkt.

**15** — `backgroundCheck` trägt `camunda:asyncBefore`, als einziger Arbeiter der
Suite. Beim Start entstehen Ausführung und Job, aber keine Aktivitätsinstanz, und
die Engine liefert überhaupt keine Instanz-Id. Genau daran meldete das Werkzeug
so ein Kind früher als *finished*, mit leeren Werten, und erwähnte es nie wieder.
Erwartet: `status` ist `waiting`, und der Wert `checkResult` erscheint im
`finishedSinceLastTurn` der Runde **nach** dem Job. Ohne diese Datei war der Fall
von Hand nicht nachstellbar — er wurde nur durch zwei Unit-Tests belegt.

## Was diese Dateien nicht prüfen

Ob die Werkzeugbeschreibungen ein Modell zu einer **sinnvollen** Reihenfolge
bringen. Das entscheidet das Modell, und dafür gibt es hier keine Zusicherung —
nur die Beobachtung im Lauf.
