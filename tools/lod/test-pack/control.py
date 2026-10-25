"""Submit one JSON request to the opt-in client probe without overwriting pending work."""
import json,pathlib,sys
root=pathlib.Path(__file__).resolve().parents[3]/'run/lod-aerial'
command=json.loads(sys.argv[1]);root.mkdir(parents=True,exist_ok=True)
if (root/'control.json').exists():raise SystemExit('Previous control request has not been consumed')
(root/'control.tmp').write_text(json.dumps(command))
(root/'control.tmp').replace(root/'control.json')
