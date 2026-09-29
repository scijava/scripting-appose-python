# Known gaps and tradeoffs

The resident-worker prototype (`_internal` package) trades some simplicity
for speed. This file records what it gives up, what is still missing, and
what would fix each item properly, so that the tradeoffs are chosen on
purpose when the code moves to its permanent homes (see [Where the code
goes](#where-the-code-goes)).

## Concurrency: one worker per environment

**Now:** Runs on the same *worker* execute one at a time, because Appose
reports worker output without saying which task produced it. Serializing
runs per worker is the only way to attribute output correctly.
`ResidentWorkerService` gives each environment exactly one worker, so in
practice runs on the same environment are serialized too. Runs on different
environments proceed in parallel.

**Why not a pool:** `LazyEnvironment` and `ResidentWorker` already allow
several workers per environment. The one-worker rule is only a policy in
`ResidentWorkerService`. It stays because a pool has costs that users would
notice:

- Each worker holds its own copy of whatever a script loads. Two workers
  running a GPU model need twice the GPU memory.
- State kept with `task.export(...)` lives in one worker. A run that lands
  on a different worker loads the model again, so run times would vary
  unpredictably.

**Proper fix:** Have Appose attribute output to tasks, e.g. by having the
Python worker redirect `sys.stdout`/`sys.stderr` per task thread and tag
each line with the task's UUID. Then a worker could run tasks concurrently,
and serialization would be needed only when the script itself is not
thread-safe (which is common for GPU models). A pool, if ever wanted, should
be opt-in per environment and capped.

## Worker lifetime and memory

**Now:** A worker lives until one of these happens:

- the application exits;
- the SciJava context is disposed;
- its environment's configuration changes (the old worker is released);
- it is killed by the cancel timeout (see below);
- it crashes.

Until then it keeps all of its memory, including GPU memory.
`ResidentWorkerService.releaseAll()` exists, but nothing in the UI calls it.

**Gaps:**

- **No way for a user to free memory.** A *Plugins ▸ Scripting ▸ Release
  Python Workers* command is a few lines, and should come first.
- **Edited helper modules are not reloaded.** Each run gets a fresh
  namespace, but `sys.modules` persists. If a script imports a local
  `helpers.py` and the user edits it, the old version keeps running until the
  worker restarts. This will confuse people during development. The release
  command fixes it by hand. Checking the modification times of imported
  local modules could fix it automatically, but may not be worth the effort.
- **No idle timeout, on purpose.** From inside the process, "idle" and
  "finished" look the same (scikit-ops design 0019 reaches the same
  conclusion). Freeing memory should be an explicit action or a rule
  applied before a known-heavy task, not a timer.

## Cancelation

**Now:** Canceling a run's SciJava task sends a cancel request to the Appose
task. Scripts that check `task.cancel_requested` stop by themselves, and
their worker stays alive. If the script has not stopped after
`SciJavaTasks.CANCEL_GRACE_MILLIS` (3 s), its worker process is killed.

**Tradeoffs:**

- Killing the worker also throws away everything it held, including models
  kept with `task.export(...)`, so the next run starts cold. That is the
  right trade for a script that ignores cancelation, but users may not
  expect it.
- The 3-second grace period is a guess. A script that checks
  `cancel_requested` only between slow steps (e.g. once per image of a
  batch) may get killed even though it would have stopped on its own.
  Making the grace period configurable per script (e.g. a `#@script`
  attribute) would be cheap.

**Missing:** Builds cannot be canceled at all. The build task's Cancel button
does nothing. This needs Appose core support: the builders run pixi, uv or
micromamba as subprocesses, which could be destroyed, but the environment
directory would then need cleaning up.

## Environment builds

**Now:** `LazyEnvironment` builds each environment once per instance.
Builds of same-named environments are serialized, because they share a
directory: e.g. an environment file edited while its previous version is
still building.

**Gaps:**

- **The lock only works within one JVM.** Two processes building the same
  environment at once can still collide, e.g. two Fiji instances, or Fiji
  and a Python front end using the same Appose directory. That needs a
  file lock in Appose core.
- **Changes are detected by content only.** A worker is replaced when the
  environment file's *contents* change (compared in memory). Appose's own
  up-to-date check handles whether the directory on disk needs rebuilding.
- **Up-to-date checks show a task too.** The first run in a session shows a
  "Building environment" task even when nothing needs building, because the
  up-to-date check happens inside the build. It usually vanishes within a
  second, but it flickers. Appose core could report "already up to date"
  before a build starts.

## Output routing

**Now:** Worker stdout and stderr reach the script's output panes by parsing
Appose's debug messages (`[WORKER-n] line`, `[SERVICE-n] <INVALID> line`).
Because stderr is read separately from stdout, the wrapper writes a unique
end-marker line to stderr, and the engine waits up to 2 s for it before
returning, so that late stderr lines are not lost or misattributed.

The same lines, plus `task.update` messages and the traceback of a failed
run, also go to the run's SciJava task logger (`Task#log()`), and a
build's tool output goes to the build task's logger. The task monitor's log
window shows them.

**Tradeoff:** Task loggers retain nothing, so the log window shows only what
is logged after it is opened. And since the task monitor drops tasks as soon
as they finish, a build that fails quickly cannot have its log opened at
all; its error message is the
only record. If this proves annoying, `DefaultTask` could keep a history.

**Fragile because:** The whole mechanism depends on debug message formats,
which are not API. Appose core should offer structured stdout/stderr
callbacks on `Service`, ideally tagged by task (see
[Concurrency](#concurrency-one-worker-per-environment)), making both the
parsing and the end marker unnecessary.

## Shared memory

**Now:** Image outputs converted to another type (e.g. `Img`) are copied,
then their shared memory is unlinked. By Appose convention, the Java side
frees shared memory, even memory the worker allocated. Before the resident
worker, every such output leaked one block.

**Gaps:**

- **Outputs kept as `NDArray` are never unlinked.** Outputs declared as
  `NDArray` or `Object` are handed to the caller as is. The caller must call
  `shm().unlinkOnClose(true)` before closing them, and nothing tells them
  so.
- **Exported inputs pin memory.** If a script exports an object that
  references an input array (e.g. `task.export(img=image)`), the worker keeps
  that shared memory mapped after Java has unlinked it. On POSIX the memory
  stays valid but held; on Windows the named block stays alive. It is freed
  when the worker exits.
- **Not verified.** The unlink fix is covered only indirectly by tests;
  nothing checks directly that blocks are freed. A shared memory pool in
  Appose core (planned) would make ownership explicit.

## Not yet tried

- **Real Fiji GUI.** All tests are headless. The build and run tasks have not
  been looked at in Fiji's task widget, and cancel has not been clicked
  there.
- **Windows.** Neither the numpy init workaround with a resident worker, nor
  killing workers, nor the end-marker handshake has been tested there.
- **Long sessions.** Memory growth over many runs (e.g. exported state,
  leaked blocks) has not been measured.

## Where the code goes

| Class | Destination |
| --- | --- |
| `BuildListener`, `LazyEnvironment`, `ResidentWorker` | Appose core |
| `ResidentWorkerService`, `SciJavaTasks` | `scijava/scijava-appose` |
| `NDArrayToImgConverter`, `RAIToNDArrayConverter`, ImageJ2 dependencies | `fiji/fiji-appose` |

Planned changes in Appose core that would retire workarounds here:

- structured, task-tagged worker output;
- build cancelation;
- a file lock for concurrent builds;
- a report that an environment is already up to date, before the build
  starts;
- a shared memory pool.
