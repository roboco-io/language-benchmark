import os
import shutil
import subprocess
import tempfile
import time
import unittest

SKILL = os.path.realpath(os.path.join(os.path.dirname(__file__), ".."))
FAKES = os.path.join(SKILL, "tests", "fakes")
HURL = os.path.join(FAKES, "hurl-mini")


def docker_up():
    try:
        return subprocess.run(["docker", "info"], capture_output=True, timeout=20).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


DOCKER = docker_up()


class MeasureBase(unittest.TestCase):
    def setUp(self):
        # 스크립트를 임시 BASE로 복사해 로그가 리포를 더럽히지 않게 한다.
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.base = os.path.join(self.tmp, "base")
        shutil.copytree(os.path.join(SKILL, "scripts"), self.base)

    def app(self, name):
        dst = os.path.join(self.tmp, "app-" + name)
        shutil.copytree(os.path.join(FAKES, "app-" + name), dst)
        return dst

    def run_script(self, script, *args, env=None, timeout=300, cwd=None):
        e = dict(os.environ, **(env or {}))
        return subprocess.run(["bash", os.path.join(self.base, script), *args],
                              capture_output=True, text=True, env=e, timeout=timeout, cwd=cwd)


class HostMeasureTest(MeasureBase):
    def test_host_pass(self):
        r = self.run_script("measure.sh", self.app("ok"), HURL)
        self.assertEqual(r.stdout.strip(), "1,1", r.stderr)

    def test_missing_start_sh_scores_zero(self):
        empty = os.path.join(self.tmp, "empty")
        os.makedirs(empty)
        r = self.run_script("measure.sh", empty, HURL)
        self.assertEqual(r.stdout.strip(), "0,0")

    def test_measure_waits_for_slow_start(self):
        # app-slow는 ../app-ok/server.py를 쓰므로 app-ok도 같은 부모 아래에 둔다.
        self.app("ok")
        r = self.run_script("measure.sh", self.app("slow"), HURL, env={"START_SEC": "90"})
        self.assertEqual(r.stdout.strip(), "1,1", r.stderr)

    def test_cleanup_kills_child_listeners(self):
        self.app("ok")
        repo = self.app("forks")
        r = self.run_script("measure.sh", repo, HURL)
        self.assertEqual(r.stdout.strip(), "1,1", r.stderr)
        time.sleep(1)
        out = subprocess.run(["lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-Fpn"],
                             capture_output=True, text=True).stdout
        pids = [l[1:] for l in out.splitlines() if l.startswith("p")]
        owned = []
        for pid in pids:
            cwd = subprocess.run(["lsof", "-a", "-p", pid, "-d", "cwd", "-Fn"],
                                 capture_output=True, text=True).stdout
            if self.tmp in cwd:
                owned.append(pid)
        self.assertEqual(owned, [])

    def test_dead_start_sh_returns_early(self):
        t0 = time.time()
        repo = self.app("dies")
        # driver.sh처럼 리포 안을 cwd로 measure.sh를 실행한다(자신·조상의 cwd가 리포 안이어도 조기 종료해야 한다).
        r = self.run_script("measure.sh", repo, HURL, env={"START_SEC": "120"}, cwd=repo)
        self.assertEqual(r.stdout.strip(), "0,0")
        self.assertLess(time.time() - t0, 30)

    def test_sibling_dir_with_shared_prefix_is_not_owned(self):
        # X-fu의 서버(명령줄에 X-fu 경로 포함)를 X의 프로세스로 오판해 채점·종료하면 안 된다.
        repo = self.app("ok")
        with open(os.path.join(repo, "start.sh"), "w") as f:
            f.write("#!/bin/bash\nexit 0\n")
        sib = repo + "-fu"
        shutil.copytree(os.path.join(FAKES, "app-ok"), sib)
        env = dict(os.environ, PORT="47123")
        srv = subprocess.Popen(["python3", os.path.join(sib, "server.py")], cwd=self.tmp, env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(srv.kill)
        time.sleep(1)
        r = self.run_script("measure.sh", repo, HURL, env={"START_SEC": "10"})
        self.assertEqual(r.stdout.strip(), "0,0")
        self.assertIsNone(srv.poll(), "형제 디렉터리 서버가 종료됨")


@unittest.skipUnless(DOCKER, "docker 미기동")
class DockerMeasureTest(MeasureBase):
    def tearDown(self):
        # 테스트가 만든 lb-* 컨테이너·이미지만 정리한다.
        ids = subprocess.run(["docker", "ps", "-aq", "--filter", "name=lb-t3"],
                             capture_output=True, text=True).stdout.split()
        if ids:
            subprocess.run(["docker", "rm", "-f", *ids], capture_output=True)
        subprocess.run(["docker", "image", "rm", "-f", "lb/t3-ok", "lb/t3-bad"], capture_output=True)

    def test_docker_pass(self):
        r = self.run_script("measure_docker.sh", self.app("ok"), "t3-ok", HURL,
                            env={"DOCKER_BUILD_SEC": "300"})
        self.assertEqual(r.stdout.strip(), "1,1", r.stderr)
        left = subprocess.run(["docker", "ps", "-a", "--filter", "name=lb-t3", "-q"],
                              capture_output=True, text=True).stdout.strip()
        self.assertEqual(left, "")

    def test_docker_build_failure_scores_zero(self):
        t0 = time.time()
        r = self.run_script("measure_docker.sh", self.app("badbuild"), "t3-bad", HURL,
                            env={"DOCKER_BUILD_SEC": "60"})
        self.assertEqual(r.stdout.strip(), "0,0")
        self.assertLess(time.time() - t0, 60)

    def test_missing_dockerfile_scores_zero(self):
        repo = self.app("ok")
        os.remove(os.path.join(repo, "Dockerfile"))
        r = self.run_script("measure_docker.sh", repo, "t3-ok", HURL)
        self.assertEqual(r.stdout.strip(), "0,0")


if __name__ == "__main__":
    unittest.main()
