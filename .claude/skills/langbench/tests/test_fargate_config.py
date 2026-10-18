import os, subprocess, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
FG = os.path.join(os.path.dirname(HERE), "fargate")
sys.path.insert(0, FG)
import orchestrate  # noqa: E402  (boto3 없이도 import돼야 한다)


class FargateConfig(unittest.TestCase):
    def test_s3_prefix(self):
        self.assertEqual(orchestrate.s3_prefix("EXP-002"), "exp002/")

    def test_task_tags(self):
        tags = orchestrate.task_tags("EXP-002")
        self.assertIn({"key": "experiment", "value": "EXP-002"}, tags)
        self.assertIn({"key": "project", "value": "langbench"}, tags)

    def test_load_config_example(self):
        cfg = orchestrate.load_config(os.path.join(FG, "fargate.env.example"))
        self.assertEqual(cfg["S3_BUCKET"], "langbench-exp001-779411790546")
        self.assertEqual(len(cfg["SUBNETS"]), 3)

    def test_load_config_missing_key(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as f:
            f.write("AWS_REGION=us-east-1\n")
        try:
            with self.assertRaises(SystemExit):
                orchestrate.load_config(f.name)
        finally:
            os.unlink(f.name)

    def test_exp_required(self):
        r = subprocess.run([sys.executable, os.path.join(FG, "orchestrate.py"), "/tmp/x"], capture_output=True)
        self.assertEqual(r.returncode, 2)

    def test_local_prep_has_no_language_case(self):
        with open(os.path.join(FG, "local_prep.sh")) as f:
            body = f.read()
        for lang in ("typescript)", "python)", "go)", "rust)", "cpp)"):
            self.assertNotIn(lang, body)
        self.assertIn("lb_static_scans", body)
        self.assertIn("langreg.py", body)


if __name__ == "__main__":
    unittest.main()
