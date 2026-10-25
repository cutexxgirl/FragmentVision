"""Exercise the exporter boundary, not a fixture that already has natural=1."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import export_world as canonical
try:
    import export_section_arrays as fast
except ImportError:
    fast = None

@unittest.skipIf(fast is None, 'numpy required for parity')
class LeafExportCheck(unittest.TestCase):
    def test_real_export_mask(self):
        states = [{'Name': 'minecraft:oak_leaves', 'Properties':
                   {'persistent': p, 'waterlogged': w}}
                  for p, w in [('false', 'false'), ('true', 'false'), ('false', 'true')]]
        blocks = [i % 3 for i in range(4096)]
        section = (states, blocks, ['minecraft:plains'], [0]*64, None, None)
        with tempfile.TemporaryDirectory() as tmp, patch.object(canonical.Anvil, 'chunk', return_value={0: section}):
            root = Path(tmp)
            for enabled in [False, True]:
                a, b = root/f'a{enabled}', root/f'b{enabled}'
                box = [(0,0,0,2,4,4)]
                canonical.export(root,a,(0,0,0),(4,4,4),box,simplify_leaves=enabled)
                fast.export(root,b,(0,0,0),(4,4,4),box,simplify_leaves=enabled)
                palette, ids, _, mask = fast.read(a)
                self.assertEqual(mask,fast.read(b)[3])
                for i, material in enumerate(ids):
                    state = palette[material][0]
                    expected = enabled and i%4<2 and 'persistent=false' in state and 'waterlogged=false' in state
                    self.assertEqual(mask[i],int(expected))
                if enabled:
                    self.assertGreater(sum(mask),0)
            protected = root/'protected'
            canonical.export(root,protected,(0,0,0),(4,4,4),box,
                             protected_boxes=box,simplify_leaves=True)
            self.assertEqual(sum(fast.read(protected)[3]),0)
