import unittest
from astra_linux.live import HoverRequest


class HoverTest(unittest.TestCase):
    def setUp(self):
        self.pending = {}; self.next_id = 0; self.opened = []
        def schedule(delay, fn):
            self.assertEqual(delay, 500)
            self.next_id += 1; self.pending[self.next_id] = fn
            return self.next_id
        self.hover = HoverRequest(schedule, lambda key: self.pending.pop(key), lambda item: self.opened.append(item['id']))

    def test_motion_opens_only_final_channel_after_dwell(self):
        for key in ['first', 'second', 'third', 'third']:
            self.hover.request({'id':key})
        self.assertEqual(len(self.pending), 1)
        self.assertFalse(self.opened)
        self.pending.popitem()[1]()
        self.assertEqual(self.opened, ['third'])
        self.assertEqual(self.next_id, 3)

    def test_leaving_or_closing_cancels_pending_preview(self):
        self.hover.request({'id':'first'})
        self.hover.cancel()
        self.assertFalse(self.pending)
        self.assertFalse(self.opened)
