import json
import os
import tempfile
import unittest

import runtime_parse


def summary(rate, p50, p95, p99, failed):
    return {"metrics": {
        "http_reqs": {"count": 1, "rate": rate},
        "http_req_duration": {"p(50)": p50, "p(95)": p95, "p(99)": p99},
        "http_req_failed": {"value": failed},
    }}


class RuntimeParse(unittest.TestCase):
    def write(self, data):
        fd, path = tempfile.mkstemp(suffix=".json")
        self.addCleanup(os.unlink, path)
        with os.fdopen(fd, "w") as f:
            json.dump(data, f)
        return path

    def test_k6_summary(self):
        p = self.write(summary(812.5, 12.1, 40.0, 88.8, 0.002))
        self.assertEqual(runtime_parse.k6_summary(p),
                         {"rps": 812.5, "p50_ms": 12.1, "p95_ms": 40.0, "p99_ms": 88.8, "error_rate": 0.2})

    def test_k6_summary_all_failed(self):
        p = self.write(summary(50, 1.0, 2.0, 3.0, 1.0))
        self.assertEqual(runtime_parse.k6_summary(p)["error_rate"], 100.0)

    def test_mem_mb_units(self):
        self.assertEqual(runtime_parse.mem_mb("512KiB / 1GiB"), 0.5)
        self.assertEqual(runtime_parse.mem_mb("1.5GiB / 2GiB"), 1536.0)
        self.assertEqual(runtime_parse.mem_mb("87.2MiB / 1GiB"), 87.2)


if __name__ == "__main__":
    unittest.main()
