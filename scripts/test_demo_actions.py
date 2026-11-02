#!/usr/bin/env python3
"""The corrective actions of demo_dataset.py must be reachable through the rules of remediation-service."""
import ast
import importlib.util
from pathlib import Path
import unittest
from unittest import mock

STATUSES = {'TODO', 'IN_PROGRESS', 'DONE', 'VERIFIED', 'CANCELLED'}
MAX_ACTIONS = 20
MAX_TEXT = 2000


def missions():
    source = (Path(__file__).resolve().parent / 'demo_dataset.py').read_text()
    for node in ast.parse(source).body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], 'id', None) == 'MISSIONS':
            return ast.literal_eval(node.value)
    raise ValueError('MISSIONS absent du jeu de données')


class DemoActions(unittest.TestCase):
    def setUp(self):
        self.findings = [(m, f) for m in missions() for f in m.get('findings', [])]
        self.planned = [(m, f) for m, f in self.findings if f.get('actions')]
        self.actions = [a for _, f in self.planned for a in f['actions']]

    def test_only_issued_findings_have_actions(self):
        for mission, finding in self.planned:
            self.assertGreaterEqual(mission['index'], 27, mission['title'])
            self.assertNotEqual(finding['decision'], 'REJECT', finding['title'])

    def test_every_status_is_shown_and_some_follow_ups_stay_to_plan(self):
        self.assertEqual({a['status'] for a in self.actions}, STATUSES)
        issued = [f for m, f in self.findings if m['index'] >= 27 and f['decision'] != 'REJECT']
        self.assertTrue(any(not f.get('actions') for f in issued))

    def test_each_status_has_the_texts_its_transitions_require(self):
        for action in self.actions:
            status = action['status']
            self.assertIn(status, STATUSES)
            self.assertTrue(1 <= action['due'] <= 365, action['description'])
            self.assertLessEqual(len(action['description']), MAX_TEXT)
            if status in ('DONE', 'VERIFIED') or 'return' in action:
                for key in ('progress', 'completion', 'attachment'):
                    self.assertIn(key, action, action['description'])
            elif status == 'IN_PROGRESS':
                self.assertIn('progress', action, action['description'])
            if status == 'VERIFIED':
                self.assertIn('verification', action, action['description'])
            if status == 'CANCELLED':
                self.assertIn('cancel', action, action['description'])

    def test_closure_needs_every_action_verified_or_cancelled(self):
        for _, finding in self.planned:
            target = finding.get('followUp')
            if target:
                self.assertIn(target, ('VERIFICATION', 'CLOSED'))
                statuses = {a['status'] for a in finding['actions']}
                self.assertIn('VERIFIED', statuses, finding['title'])
                self.assertLessEqual(statuses, {'VERIFIED', 'CANCELLED'}, finding['title'])
            if target == 'CLOSED':
                self.assertTrue(finding.get('closure'), finding['title'])

    def test_descriptions_identify_the_action_within_its_follow_up(self):
        for _, finding in self.planned:
            descriptions = [a['description'] for a in finding['actions']]
            self.assertEqual(len(descriptions), len(set(descriptions)), finding['title'])
            self.assertLessEqual(len(descriptions), MAX_ACTIONS)


class WaitFollowUps(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parent / 'seed-demo-missions.py'
        spec = importlib.util.spec_from_file_location('seed_demo_missions', path)
        self.seed = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.seed)
        sleep = mock.patch.object(self.seed.time, 'sleep')
        sleep.start()
        self.addCleanup(sleep.stop)

    def test_mission_not_yet_known_is_waited_for(self):
        api = mock.Mock()
        api.call.side_effect = [self.seed.ApiError('HTTP 404', 404), {'followUps': [{'title': 'Constat'}]}]
        self.assertIn('Constat', self.seed.wait_follow_ups(api, 'mission', {'Constat'}))
        self.assertEqual(api.call.call_count, 2)

    def test_other_errors_stop_the_seed(self):
        api = mock.Mock()
        api.call.side_effect = self.seed.ApiError('HTTP 403', 403)
        with self.assertRaises(self.seed.ApiError):
            self.seed.wait_follow_ups(api, 'mission', {'Constat'})
        self.assertEqual(api.call.call_count, 1)

    def test_mission_that_never_arrives_stops_the_seed(self):
        api = mock.Mock()
        api.call.side_effect = self.seed.ApiError('HTTP 404', 404)
        with self.assertRaisesRegex(self.seed.ApiError, 'suivis non reçus'):
            self.seed.wait_follow_ups(api, 'mission', {'Constat'})


if __name__ == '__main__':
    unittest.main()
