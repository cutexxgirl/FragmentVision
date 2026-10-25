"""Array exporter parity with canonical Anvil decoding, including unknown space."""
from pathlib import Path
import tempfile,unittest
import export_world
from test_export_world import region_grid
try:
    import export_section_arrays as fast
except ImportError:
    fast=None

@unittest.skipIf(fast is None,'Optional numpy array-export diagnostic')
class ArrayExporterCheck(unittest.TestCase):
    def test_canonical_parity_and_replay(self):
        import numpy as np
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);region_grid(root)
            for index,(origin,size) in enumerate([((0,0,0),(46,24,46)),((-2,-2,-2),(50,28,50))]):
                box=[(-1,-1,-1,32,18,32)]
                old=root/f'old{index}.gz';new=root/f'new{index}.gz';replay=root/f'replay{index}.gz'
                export_world.export(root,old,origin,size,box)
                fast.export(root,new,origin,size,box);fast.export(root,replay,origin,size,box)
                a,ai,al,an=fast.read(old);b,bi,bl,bn=fast.read(new)
                mapping=np.array([b.index(value) for value in a])
                self.assertTrue(np.array_equal(np.where(ai<0,-1,mapping[np.maximum(ai,0)]),bi))
                self.assertEqual(al,bl);self.assertEqual(an,bn);self.assertEqual(new.read_bytes(),replay.read_bytes())
                with self.assertRaises(FileExistsError):fast.export(root,new,origin,size,box)
    def test_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for size in [(2,2,2),(145,384,145),(142,512,142)]:
                with self.assertRaises(ValueError):fast.export(root,root/'bad.gz',(0,0,0),size,[])

if __name__=='__main__':unittest.main()
