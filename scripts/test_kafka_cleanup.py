import unittest
from kafka_cleanup import offsets_drained

class CleanupOffsetsTest(unittest.TestCase):
    def test_consumed_and_never_used_empty_partition(self):
        self.assertTrue(offsets_drained('notification-service-v1 topic 0 5 5 0 x\n  notification-service-v1 topic 1 - 0 - x'))
    def test_uninitialized_nonempty_partition_is_not_drained(self):
        self.assertFalse(offsets_drained('notification-service-v1 topic 0 - 2 - x'))
    def test_lag_is_not_drained(self):
        self.assertFalse(offsets_drained('notification-service-v1 topic 0 4 5 1 x'))
    def test_missing_or_malformed_offsets_are_not_drained(self):
        self.assertFalse(offsets_drained('GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG'))
        self.assertFalse(offsets_drained('notification-service-v1 incomplete'))

if __name__ == '__main__': unittest.main()
