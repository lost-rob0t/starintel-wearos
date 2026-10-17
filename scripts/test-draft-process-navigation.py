#!/usr/bin/env python3
"""Host checks for drawer scoping and bounded off-screen navigation; no device commands."""
import importlib.util
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('draft_process', pathlib.Path(__file__).with_name('test-quasar-draft-process-death.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

class NavigationTest(unittest.TestCase):
    def tree(self, visible):
        return ET.fromstring('<hierarchy><node text="Add record" bounds="[0,0][360,80]"/>'
            '<node content-desc="Workspace destinations" bounds="[0,80][300,720]">' +
            ('<node text="Add record" bounds="[0,100][300,150]"/>' if visible else '') + '</node></hierarchy>')

    def test_covered_route_card_is_not_a_drawer_destination(self):
        self.assertIsNone(fixture.find_node(self.tree(False), lambda n: n.get('text') == 'Add record', 'Workspace destinations'))

    def test_offscreen_destination_is_scrolled_then_tapped_once(self):
        calls = []
        trees = iter([self.tree(False), self.tree(True)])
        fixture.nodes = lambda: next(trees)
        fixture.text = lambda *args, **kwargs: calls.append(args)
        with tempfile.TemporaryDirectory() as directory:
            fixture.OUT = pathlib.Path(directory)
            fixture.tap_destination('Add record')
        self.assertEqual([call[2] for call in calls], ['swipe', 'tap'])
        self.assertEqual(calls[-1][-2:], ('150', '125'))

    def test_missing_destination_fails_after_eight_scrolls_without_tapping(self):
        calls = []
        fixture.nodes = lambda: self.tree(False)
        fixture.text = lambda *args, **kwargs: calls.append(args)
        with tempfile.TemporaryDirectory() as directory:
            fixture.OUT = pathlib.Path(directory)
            with self.assertRaisesRegex(AssertionError, 'bounded scroll'):
                fixture.tap_destination('Add record')
        self.assertEqual(len(calls), 8)
        self.assertTrue(all(call[2] == 'swipe' for call in calls))

if __name__ == '__main__':
    unittest.main()
