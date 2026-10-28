"""Re-derive MiTv.JOBS: query every (cluster, region) pair and greedily pick the smallest set covering all channels."""
import sys, os
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0, os.path.dirname(__file__))
import mitv

CC = "in us gb br mx my id ph th vn sg es de fr it ru pt pl nl tr ar co cl pe ua sa ae eg za ng kr jp au ca eu at ch be se no dk fi ie gr cz ro hu sk bg rs hr nz pk bd lk np tw hk lu li mc ma tn dz ke gh".split()

def ids(job):
    try:
        return job, {c["id"] for c in mitv.chans(mitv.get("/mitv/guide", job[0], region=job[1]))}
    except Exception:
        return job, set()

S = dict(ThreadPoolExecutor(16).map(ids, [(cl, r) for cl in mitv.CL for r in CC]))
left = set().union(*S.values())
print(len(left), "channels")
while left:
    job = max(S, key=lambda k: len(S[k] & left))
    if not S[job] & left:
        break
    print(job, len(S[job] & left))
    left -= S[job]
