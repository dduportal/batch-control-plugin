"""e2e-26 #32: a case-only rename keeps the job's activation, also across a restart (wave-B contract #32; JenkinsRule
rows T-06a-112/113, ActivationCaseRenameTest).

usage: python case_rename.py [ARX]   rows: out/case_rename.jsonl, shots: screenshots/run-26/R26-32-*.png
The defect needs a case-insensitive file system under JENKINS_HOME/batch-control (default APFS, NTFS), where
activations/r26-Nightly.xml and activations/r26-nightly.xml are one file. The CI stack is Linux (ext4 or overlay,
case-sensitive): there this unit is the GUARD of the scenario and cannot be red. Section A probes the file system of
batch-control/activations/ and names its kind in every check. The premise can be built on a macOS host with
compose.ci-fs.yml (the store on a host directory: Docker Desktop's file sharing keeps APFS case-insensitive):

  BC_CI_FS_DIR=<empty host dir> BC_COMPOSE_EXTRA=$PWD/e2e/compose.ci-fs.yml e2e/r26/wrap.sh unit r26-case-rename

Items (deleted and created again by A): r26-Nightly with a `* * * * *` timer and r26-Weekly; folders r26-Team and r26-Ops,
each with an `etl` job (r26-Team/etl with the timer). The timer jobs are approval-required with blockTimer and
blockUpstream cleared, so only the activation decides whether the timer runs (D-46). All four are activated through the
real flow (requester files, approver-1 approves). Restarts Jenkins (section X): a `last` unit.

A  arrangement as above; the file system kind of batch-control/activations/ (a probe file looked up under another case).
R  the case-only renames through the script console, which is the user's path here (the UI refuses a case-only rename;
   a script or Job DSL calls Job.renameTo / AbstractItem.renameTo): r26-Nightly -> r26-nightly, folder r26-Team ->
   r26-team; guards with a different name: r26-Weekly -> r26-weekly-renamed, folder r26-Ops -> r26-ops-renamed. The new
   names answer; in the same session the pages still say activated.
X  restart (docker restart). Guards: r26-weekly-renamed and r26-ops-renamed/etl are still activated. Contract #32:
   r26-nightly and r26-team/etl are still activated (the requester's job page in the browser) and their timer is
   accepted: a build started by the timer at or after the first minute of the restarted JVM (the restart is made
   between two minutes with no timer run queued, so none is restored from queue.xml), and no TRIGGER_BLOCKED record.

Not here: the upstream run (T-06a-112/113 assert it), Job DSL itself (not installed in the e2e image; it calls the same
renameTo). On a case-sensitive file system this unit passes with and without the fix; with the premise of a
case-insensitive store it fails without the fix (after the restart the job is no longer activated and its timer is
refused)."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J  # noqa: E402

lib.LOGNAME[0] = "case_rename"
USER = "requester"
TIMER = "<hudson.triggers.TimerTrigger><spec>* * * * *</spec></hudson.triggers.TimerTrigger>"
OLD = {"job": "r26-Nightly", "folder": "r26-Team", "guard_job": "r26-Weekly", "guard_folder": "r26-Ops"}
NEW = {"job": "r26-nightly", "folder": "r26-team", "guard_job": "r26-weekly-renamed", "guard_folder": "r26-ops-renamed"}
TIMED = [NEW["job"], NEW["folder"] + "/etl"]       # the case-only renamed jobs (with the timer)
GUARDS = [NEW["guard_job"], NEW["guard_folder"] + "/etl"]
S = {"fs": "unknown file system"}


def fs_kind():
    """'case-insensitive' / 'case-sensitive' file system of batch-control/activations/ (a probe file created as the jenkins
    user and looked up under its lower-case name: same inode = one file)."""
    d = f"{lib.STORE}/activations"
    n = int(time.time())
    rc, out = lib.sh(f"mkdir -p {d} && touch {d}/R26CaseProbe-{n} && stat -c %i {d}/R26CaseProbe-{n} && "
                     f"(stat -c %i {d}/r26caseprobe-{n} 2>/dev/null || echo none); rm -f {d}/R26CaseProbe-{n}")
    ids = out.split()
    if rc != 0 or len(ids) != 2:
        return None, out[-200:]
    return ("case-insensitive" if ids[0] == ids[1] else "case-sensitive"), ids


def timer_builds(job, since_ms):
    """Builds of `job` started at or after `since_ms` (controller clock), with their causes."""
    r = api("admin", J(job) + "/api/json?tree=builds[number,result,timestamp,actions[causes[shortDescription]]]")
    out = []
    for b in (r.json().get("builds", []) if r.status_code == 200 else []):
        causes = [c.get("shortDescription", "") for a in b.get("actions", []) if a for c in a.get("causes", []) or []]
        if (b.get("timestamp") or 0) >= since_ms:
            out.append({"number": b["number"], "result": b.get("result"), "causes": causes})
    return out


def state_files():
    """The activation-state files whose name mentions r26- (evidence for the report; the name format is the plugin's)."""
    return lib.sh(f"ls -A {lib.STORE}/activations | grep -i r26- ; true")[1].split()


def rename(old, new):
    """Item.renameTo through the script console (the user's path for a case-only rename); 'renamed' or the error."""
    return lib.gv(f"""def i = jenkins.model.Jenkins.get().getItemByFullName('{old}')
if (i == null) return 'missing {old}'
try {{ i.renameTo('{new}'); return 'renamed ' + i.fullName }} catch (Throwable t) {{ return 'THREW ' + t }}""")


# ---------------------------------------------------------------- sections
def sec_A():
    for name in list(OLD.values()) + list(NEW.values()):
        lib.delete(name)
    left = [n for n in list(OLD.values()) + list(NEW.values()) if api("admin", J(n) + "/api/json").status_code != 404]
    made = [lib.create(OLD["job"], lib.job_xml("fs", shell="echo r26 tick", triggers=TIMER)),
            lib.create(OLD["guard_job"], lib.job_xml("fs", shell="echo r26")),
            lib.create(OLD["folder"], lib.FOLDER_XML), lib.create(OLD["guard_folder"], lib.FOLDER_XML),
            lib.create(OLD["folder"] + "/etl", lib.job_xml("fs", shell="echo r26 tick", triggers=TIMER)),
            lib.create(OLD["guard_folder"] + "/etl", lib.job_xml("fs", shell="echo r26"))]
    jobs = [OLD["job"], OLD["folder"] + "/etl", OLD["guard_job"], OLD["guard_folder"] + "/etl"]
    props = {j: lib.set_property(j, approval=True, timer=False, upstream=False) for j in jobs}
    live = {j: lib.activate(j, reason="e2e-26 #32 arrangement") for j in jobs}
    kind, probe = fs_kind()
    S["fs"] = f"{kind} file system" if kind else "unknown file system"
    note("A", "the file system of batch-control/activations/", kind=kind, probe=probe,
         meaning="the defect can only show on a case-insensitive file system; on a case-sensitive one this unit is the guard")
    sw = lib.switches()
    check("A", f"arrangement ({S['fs']}): run control on; r26-Nightly (timer) and r26-Team/etl (timer) approval-required "
          "with blockTimer/blockUpstream cleared, r26-Weekly and r26-Ops/etl, all four activated through the request flow",
          sw["run_control"] and not left and made == [200] * 6 and all(live.values()) and kind is not None
          and all("approvalRequired=true blockTimer=false blockUpstream=false" in p for p in props.values()),
          switches=sw, left=left, created=made, activated=live, props=props, container=lib.CONTAINER)


def sec_R():
    out = {OLD[k]: rename(OLD[k], NEW[k]) for k in OLD}
    answers = {n: api("admin", J(n) + "/api/json").status_code for n in TIMED + GUARDS}
    check("R", f"premise ({S['fs']}): the case-only renames (r26-Nightly -> r26-nightly, folder r26-Team -> r26-team) and "
          "the guard renames are applied; the new names answer",
          all(v.startswith("renamed ") for v in out.values()) and all(c == 200 for c in answers.values()),
          renames=out, answers=answers)
    note("R", "activation-state files after the renames", files=state_files())
    same = {j: lib.activation(j) for j in TIMED + GUARDS}
    check("R", f"guard ({S['fs']}): in the same session every renamed job's page still says activated",
          all(v == "activated" for v in same.values()), pages=same)


def sec_X():
    # Restart between two timer minutes (seconds 5..35 of the controller's minute) with no timer item queued or running,
    # so no timer run queued by the old JVM is restored from queue.xml; only a tick of the new JVM counts below.
    quiet = lib.wait_until(lambda: 5 <= int(lib.gv("return java.time.LocalTime.now().second")) <= 35
                           and not any(lib.L19.queue_items(j) for j in TIMED)
                           and not any(b.get("building") for j in TIMED for b in lib.builds(j)), 90, 1)
    check("X", "precondition: the restart starts between two timer minutes with no timer run queued or running",
          bool(quiet))
    secs, rc = lib.restart()
    mark = lib.now_ms()  # the restart has completed: what follows ran in the new JVM
    tick = (mark // 60000 + 1) * 60000  # the first timer minute of the new JVM
    check("X", "precondition: Jenkins restarted and the plugin is active", secs is not None and rc == 0, seconds=secs, rc=rc)
    note("X", "activation-state files after the restart", files=state_files())
    guards = {j: lib.page_state(USER, j, f"R26-32-X-guard-{j.replace('/', '-')}") for j in GUARDS}
    check("X", f"guard (SPEC 6a, {S['fs']}): the jobs renamed to another name are still activated after the restart",
          all(v == "activated" for v in guards.values()), pages=guards)
    pages = {j: lib.page_state(USER, j, f"R26-32-X-{j.replace('/', '-')}") for j in TIMED}
    check("X", f"contract #32 ({S['fs']}): after the case-only rename and a restart, r26-nightly and r26-team/etl are "
          "still activated (the requester's job page)", all(v == "activated" for v in pages.values()), pages=pages)
    started = lib.wait_until(lambda: (lambda b: b if all(b.values()) else None)(
        {j: [x for x in timer_builds(j, tick) if any("timer" in c.lower() for c in x["causes"])] for j in TIMED}), 100, 3)
    check("X", f"contract #32 ({S['fs']}): their timer is accepted after the restart (a build started by a timer minute "
          "of the restarted JVM, within 100 s, for each)", bool(started), first_tick_ms=tick,
          builds=started or {j: timer_builds(j, mark - 120000) for j in TIMED})
    blocked = {j: lib.records_since(mark, ("TRIGGER_BLOCKED",), j) for j in TIMED}
    check("X", f"contract #32 ({S['fs']}): no TRIGGER_BLOCKED record for r26-nightly or r26-team/etl after the restart",
          not any(blocked.values()), records={j: len(v) for j, v in blocked.items()})


def cleanup():
    # (Jenkins resolves item names case-insensitively, so r26-Nightly before the rename is the same item)
    st = {j: api("admin", J(j) + "/disable", "POST").status_code for j in TIMED}
    note("cleanup", "the timer jobs are disabled, so their timer does not run during the rest of the shard", http=st)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "R": sec_R, "X": sec_X}, cleanup)
