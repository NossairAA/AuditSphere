import unittest
from service_health import healthy, wait_healthy

class FakeClock:
    def __init__(self): self.now = 0.0
    def __call__(self): return self.now
    def sleep(self, seconds): self.now += seconds

class ServiceHealthTest(unittest.TestCase):
    def test_only_http_200_is_healthy(self):
        self.assertTrue(healthy('HTTP/1.1 200 \r\n'))
        for line in ['', 'HTTP/1.1 503', 'HTTP/1.1 2000', 'bash: connect: Connection refused 200']:
            self.assertFalse(healthy(line), line)
    def test_retries_until_the_restarted_service_answers(self):
        answers = iter(['', 'HTTP/1.1 503', 'HTTP/1.1 200'])
        clock = FakeClock()
        wait_healthy(lambda: next(answers), 'audit-service', clock=clock, sleep=clock.sleep)
        self.assertEqual(clock.now, 2)
    def test_returns_without_waiting_when_already_healthy(self):
        clock = FakeClock()
        wait_healthy(lambda: 'HTTP/1.1 200', 'audit-service', clock=clock, sleep=clock.sleep)
        self.assertEqual(clock.now, 0)
    def test_fails_after_the_deadline(self):
        clock = FakeClock()
        with self.assertRaisesRegex(RuntimeError, 'audit-service indisponible'):
            wait_healthy(lambda: '', 'audit-service', seconds=5, clock=clock, sleep=clock.sleep)
        self.assertEqual(clock.now, 6)

if __name__ == '__main__': unittest.main()
