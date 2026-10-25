"""Launch the isolated aerial probe; an optional lod-aerial-* world reuses pregeneration."""
import os, pathlib, re, subprocess, sys
from urllib.parse import urlparse
root=pathlib.Path(__file__).resolve().parents[3]
env=os.environ.copy()
env.setdefault('DISPLAY', ':99')
env.setdefault('ALSOFT_DRIVERS','null')
env.setdefault('JAVA_TOOL_OPTIONS','-XX:ActiveProcessorCount=4')
args=['bash','gradlew','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
proxy=urlparse(env.get('HTTPS_PROXY',env.get('https_proxy','')))
if proxy.hostname:
    for scheme in ('http','https'):
        args += [f'-D{scheme}.proxyHost={proxy.hostname}',f'-D{scheme}.proxyPort={proxy.port or 8080}']
args += ['runClient','-PlodAerial']
if len(sys.argv)>1:
    if not re.fullmatch(r'lod-aerial-[a-zA-Z0-9_-]+',sys.argv[1]):raise ValueError('Expected a probe-owned world')
    args += ['-PlodWorld='+sys.argv[1]]
raise SystemExit(subprocess.call(args,cwd=root,env=env))
