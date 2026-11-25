#!/usr/bin/env python3
"""Post the review #7 draft reply (~/workspace/c2-impl/review7/draft-reply.md)
as a comment on paylist PR #4. Extracts only the comment body between the
start/end markers; the DO-NOT-POST header/footer never leaves this box."""
import json
import subprocess
import sys

text = open('/home/hatch/workspace/c2-impl/review7/draft-reply.md').read()
start_marker = '# The comment body starts below the line.'
end_marker = '# End of draft comment body.'
i = text.index(start_marker)
j = text.index(end_marker)
chunk = text[i:j]
lines = [ln for ln in chunk.split('\n')
         if not ln.startswith('# ---') and ln != start_marker]
body = '\n'.join(lines).strip() + '\n'
assert body.startswith('[MUSE — Review #7 evidence'), body[:60]
assert body.rstrip().endswith('and the ER_CHECKREAD fix.'), body[-60:]
print('body chars:', len(body))

args = {"owner": "izzdiin", "repo": "paylist", "issue_number": 4, "body": body}
r = subprocess.run(
    ['/opt/hatch/bin/github', 'call-tool', '--name', 'add_issue_comment',
     '--arguments-json', json.dumps(args)],
    capture_output=True, text=True)
print(r.stdout)
print(r.stderr, file=sys.stderr)
sys.exit(r.returncode)
