"""Parse Kafka's group offsets without treating uninitialized nonempty partitions as drained."""
def offsets_drained(output, group='notification-service-v1'):
    rows = [line.split() for line in output.splitlines() if line.split() and line.split()[0] == group]
    if not rows:
        return False
    for row in rows:
        if len(row) < 6:
            return False
        current, end, lag = row[3:6]
        if lag == '0':
            continue
        if current == '-' and end == '0' and lag == '-':
            continue
        return False
    return True
