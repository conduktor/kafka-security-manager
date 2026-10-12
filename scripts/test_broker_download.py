"""Exercise checksum verification, mirror fallback and curl resumption over real HTTP."""

import hashlib
import importlib.util
import io
import tarfile
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "broker_tests", Path(__file__).with_name("broker-tests.py")
)
brokers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brokers)


class DownloadTest(unittest.TestCase):
    def setUp(self):
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as output:
            entry = tarfile.TarInfo("test/marker")
            entry.size = 8
            output.addfile(entry, io.BytesIO(b"verified"))
        self.payload = archive.getvalue()
        self.requests = []
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                resume = self.headers.get("Range")
                fixture.requests.append((self.path, resume))
                if self.path.startswith("/missing/"):
                    self.send_error(404)
                    return
                content = fixture.payload
                if self.path.startswith("/corrupt/"):
                    content += b"corrupted"
                offset = int(resume.removeprefix("bytes=").removesuffix("-")) if resume else 0
                self.send_response(206 if offset else 200)
                self.send_header("Content-Length", str(len(content) - offset))
                if offset:
                    self.send_header(
                        "Content-Range", f"bytes {offset}-{len(content) - 1}/{len(content)}"
                    )
                self.end_headers()
                if self.path.startswith("/partial/") and not resume:
                    self.wfile.write(content[: len(content) // 2])
                    self.close_connection = True
                else:
                    self.wfile.write(content[offset:])

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.temp = tempfile.TemporaryDirectory()
        self.cache = Path(self.temp.name) / "cache"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def cell(self, route):
        return {
            "url": f"http://127.0.0.1:{self.server.server_port}/{route}/test.tgz",
            "sha512": hashlib.sha512(self.payload).hexdigest(),
        }

    def test_unavailable_mirror_falls_back_to_verified_archive(self):
        cell = self.cell("valid")
        cell["mirrors"] = [self.cell("missing")["url"]]
        result = brokers.download(cell, self.cache)
        self.assertEqual(b"verified", (result / "marker").read_bytes())
        self.assertEqual(["/missing/test.tgz", "/valid/test.tgz"], [r[0] for r in self.requests])

    def test_interrupted_download_resumes_from_received_bytes(self):
        result = brokers.download(self.cell("partial"), self.cache)
        self.assertEqual(b"verified", (result / "marker").read_bytes())
        self.assertEqual(2, len(self.requests))
        self.assertEqual(f"bytes={len(self.payload) // 2}-", self.requests[1][1])
        self.assertEqual(self.payload, (self.cache / "test.tgz").read_bytes())

    def test_wrong_checksum_is_never_promoted_or_extracted(self):
        with self.assertRaisesRegex(RuntimeError, "SHA-512 mismatch"):
            brokers.download(self.cell("corrupt"), self.cache)
        self.assertFalse((self.cache / "test.tgz").exists())
        self.assertFalse((self.cache / "test").exists())

    def test_cached_archive_is_verified_before_extraction(self):
        self.cache.mkdir()
        (self.cache / "test.tgz").write_bytes(b"corrupted")
        with self.assertRaisesRegex(RuntimeError, "SHA-512 mismatch"):
            brokers.download(self.cell("valid"), self.cache)
        self.assertEqual([], self.requests)
        self.assertFalse((self.cache / "test").exists())


if __name__ == "__main__":
    unittest.main()
