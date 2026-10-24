"""Wait for a restarted service to answer its HTTP health probe before chaining tests."""
import time

def healthy(status_line):
    parts = status_line.split()
    return len(parts) > 1 and parts[0].startswith('HTTP/') and parts[1] == '200'

def wait_healthy(probe, label, seconds=120, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + seconds
    while not healthy(probe()):
        if clock() > deadline:
            raise RuntimeError(label + ' indisponible après nettoyage')
        sleep(1)
